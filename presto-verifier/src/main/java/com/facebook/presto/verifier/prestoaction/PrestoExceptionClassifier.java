/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.presto.verifier.prestoaction;

import com.facebook.presto.connector.thrift.ThriftErrorCode;
import com.facebook.presto.hive.HiveErrorCode;
import com.facebook.presto.jdbc.QueryStats;
import com.facebook.presto.plugin.jdbc.JdbcErrorCode;
import com.facebook.presto.spi.ErrorCodeSupplier;
import com.facebook.presto.spi.StandardErrorCode;
import com.facebook.presto.verifier.framework.ClusterConnectionException;
import com.facebook.presto.verifier.framework.PrestoQueryException;
import com.facebook.presto.verifier.framework.QueryException;
import com.facebook.presto.verifier.framework.QueryStage;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;

import java.io.EOFException;
import java.io.UncheckedIOException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.sql.SQLException;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

import static com.facebook.presto.connector.thrift.ThriftErrorCode.THRIFT_SERVICE_CONNECTION_ERROR;
import static com.facebook.presto.hive.HiveErrorCode.HIVE_CANNOT_OPEN_SPLIT;
import static com.facebook.presto.hive.HiveErrorCode.HIVE_CURSOR_ERROR;
import static com.facebook.presto.hive.HiveErrorCode.HIVE_FILESYSTEM_ERROR;
import static com.facebook.presto.hive.HiveErrorCode.HIVE_FILE_NOT_FOUND;
import static com.facebook.presto.hive.HiveErrorCode.HIVE_METASTORE_ERROR;
import static com.facebook.presto.hive.HiveErrorCode.HIVE_PARTITION_DROPPED_DURING_QUERY;
import static com.facebook.presto.hive.HiveErrorCode.HIVE_TABLE_DROPPED_DURING_QUERY;
import static com.facebook.presto.hive.HiveErrorCode.HIVE_TOO_MANY_OPEN_PARTITIONS;
import static com.facebook.presto.hive.HiveErrorCode.HIVE_WRITER_CLOSE_ERROR;
import static com.facebook.presto.hive.HiveErrorCode.HIVE_WRITER_DATA_ERROR;
import static com.facebook.presto.hive.HiveErrorCode.HIVE_WRITER_OPEN_ERROR;
import static com.facebook.presto.plugin.jdbc.JdbcErrorCode.JDBC_ERROR;
import static com.facebook.presto.spi.StandardErrorCode.ABANDONED_TASK;
import static com.facebook.presto.spi.StandardErrorCode.NO_NODES_AVAILABLE;
import static com.facebook.presto.spi.StandardErrorCode.PAGE_TRANSPORT_ERROR;
import static com.facebook.presto.spi.StandardErrorCode.PAGE_TRANSPORT_TIMEOUT;
import static com.facebook.presto.spi.StandardErrorCode.REMOTE_HOST_GONE;
import static com.facebook.presto.spi.StandardErrorCode.REMOTE_TASK_ERROR;
import static com.facebook.presto.spi.StandardErrorCode.SERVER_SHUTTING_DOWN;
import static com.facebook.presto.spi.StandardErrorCode.SERVER_STARTING_UP;
import static com.facebook.presto.spi.StandardErrorCode.SYNTAX_ERROR;
import static com.facebook.presto.spi.StandardErrorCode.TOO_MANY_REQUESTS_FAILED;
import static com.google.common.base.Functions.identity;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static java.util.Arrays.asList;
import static java.util.regex.Pattern.CASE_INSENSITIVE;

public class PrestoExceptionClassifier
        implements SqlExceptionClassifier
{
    public static final Set<ErrorCodeSupplier> DEFAULT_ERRORS = ImmutableSet.<ErrorCodeSupplier>builder()
            .addAll(asList(StandardErrorCode.values()))
            .addAll(asList(HiveErrorCode.values()))
            .addAll(asList(JdbcErrorCode.values()))
            .addAll(asList(ThriftErrorCode.values()))
            .build();

    public static final Set<ErrorCodeSupplier> DEFAULT_RETRYABLE_ERRORS = ImmutableSet.of(
            // From StandardErrorCode
            NO_NODES_AVAILABLE,
            REMOTE_TASK_ERROR,
            SERVER_SHUTTING_DOWN,
            SERVER_STARTING_UP,
            TOO_MANY_REQUESTS_FAILED,
            PAGE_TRANSPORT_ERROR,
            PAGE_TRANSPORT_TIMEOUT,
            REMOTE_HOST_GONE,
            ABANDONED_TASK,
            // From HiveErrorCode
            HIVE_CURSOR_ERROR,
            HIVE_FILE_NOT_FOUND,
            HIVE_TOO_MANY_OPEN_PARTITIONS,
            HIVE_WRITER_OPEN_ERROR,
            HIVE_WRITER_CLOSE_ERROR,
            HIVE_WRITER_DATA_ERROR,
            HIVE_FILESYSTEM_ERROR,
            HIVE_CANNOT_OPEN_SPLIT,
            HIVE_METASTORE_ERROR,
            // From JdbcErrorCode
            JDBC_ERROR,
            // From ThriftErrorCode
            THRIFT_SERVICE_CONNECTION_ERROR);

    private static final Set<ErrorCodeSupplier> DEFAULT_REQUEUABLE_ERRORS = ImmutableSet.of(
            HIVE_PARTITION_DROPPED_DURING_QUERY,
            HIVE_TABLE_DROPPED_DURING_QUERY);

    private static final Pattern TABLE_ALREADY_EXISTS_PATTERN = Pattern.compile("table.*already exists", CASE_INSENSITIVE);

    private final Map<Integer, ErrorCodeSupplier> errorByCode;
    private final Set<ErrorCodeSupplier> retryableErrors;

    private PrestoExceptionClassifier(Set<ErrorCodeSupplier> recognizedErrors, Set<ErrorCodeSupplier> retryableErrors)
    {
        this.errorByCode = recognizedErrors.stream()
                .collect(toImmutableMap(errorCode -> errorCode.toErrorCode().getCode(), identity()));
        this.retryableErrors = ImmutableSet.copyOf(retryableErrors);
    }

    public static PrestoExceptionClassifier createDefault()
    {
        return create(DEFAULT_ERRORS, DEFAULT_RETRYABLE_ERRORS);
    }

    public static PrestoExceptionClassifier create(Set<ErrorCodeSupplier> recognizedErrors, Set<ErrorCodeSupplier> retryableErrors)
    {
        Set<ErrorCodeSupplier> unrecognized = Sets.difference(retryableErrors, recognizedErrors);
        checkArgument(unrecognized.isEmpty(), "Retryable errors not recognized: %s", unrecognized);
        return new PrestoExceptionClassifier(recognizedErrors, retryableErrors);
    }

    public QueryException createException(QueryStage queryStage, Optional<QueryStats> queryStats, SQLException cause)
    {
        Optional<Throwable> clusterConnectionExceptionCause = getClusterConnectionExceptionCause(cause);
        if (clusterConnectionExceptionCause.isPresent()) {
            return new ClusterConnectionException(clusterConnectionExceptionCause.get(), queryStage);
        }

        Optional<ErrorCodeSupplier> errorCode = Optional.ofNullable(errorByCode.get(cause.getErrorCode()));
        return new PrestoQueryException(cause, errorCode.isPresent() && retryableErrors.contains(errorCode.get()), queryStage, errorCode, queryStats);
    }

    public static boolean shouldResubmit(Throwable throwable)
    {
        if (!(throwable instanceof PrestoQueryException)) {
            return false;
        }
        PrestoQueryException queryException = (PrestoQueryException) throwable;
        Optional<ErrorCodeSupplier> errorCode = queryException.getErrorCode();
        return errorCode.isPresent() && DEFAULT_REQUEUABLE_ERRORS.contains(errorCode.get())
                || isTargetTableAlreadyExistsException(queryException);
    }

    public static boolean isClusterConnectionException(Throwable t)
    {
        return getClusterConnectionExceptionCause(t).isPresent();
    }

    private static Optional<Throwable> getClusterConnectionExceptionCause(Throwable t)
    {
        while (t != null) {
            if (t instanceof SocketTimeoutException ||
                    t instanceof SocketException ||
                    t instanceof EOFException ||
                    t instanceof UncheckedIOException ||
                    t instanceof TimeoutException ||
                    (t.getClass().equals(RuntimeException.class) && t.getMessage() != null && t.getMessage().contains("Error fetching next at"))) {
                return Optional.of(t);
            }
            t = t.getCause();
        }
        return Optional.empty();
    }

    private static boolean isTargetTableAlreadyExistsException(PrestoQueryException queryException)
    {
        return queryException.getErrorCode().equals(Optional.of(SYNTAX_ERROR))
                && queryException.getQueryStage().isSetup()
                && TABLE_ALREADY_EXISTS_PATTERN.matcher(queryException.getMessage()).find();
    }
}
