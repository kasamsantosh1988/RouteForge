package com.rsa.routeforge.audit;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;

/**
 * The single {@code ROUTE_AUDIT} insert.
 * <p>
 * Plain JDBC rather than JPA: the table is append-only and written one row at a time, so an ORM
 * would contribute a session lifecycle and a mapping layer for no benefit.
 * <p>
 * Deliberately not {@code @Transactional}. A single statement under autocommit is already atomic,
 * and enlisting the audit in a caller's transaction is the wrong semantics — an audit row records
 * that a request happened, which stays true whatever the request did.
 */
@Repository
@ConditionalOnProperty(name = "engine.audit.storage-type", havingValue = "db", matchIfMissing = true)
public class RouteAuditDao  implements RouteAuditStore  {

    private static final String INSERT_SQL = """
            INSERT INTO ROUTE_AUDIT (
                AUDIT_ID, TRACE_ID, ROUTE_ID, ROUTE_PATH, HTTP_METHOD, QUERY_STRING,
                REQUEST_HEADERS, REQUEST_BODY_PATH, RESPONSE_BODY_PATH,
                OUTCOME, LOGICAL_STATUS, WIRE_STATUS, ERROR_CODE, FAILED_STEP,
                DURATION_MS, STARTED_AT, COMPLETED_AT, APP_INSTANCE
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private final JdbcTemplate jdbcTemplate;

    public RouteAuditDao(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void insert(RouteAuditRecord record) {
        jdbcTemplate.update(INSERT_SQL,
                record.getAuditId(),
                record.getTraceId(),
                record.getRouteId(),
                record.getRoutePath(),
                record.getHttpMethod(),
                record.getQueryString(),
                record.getRequestHeaders(),
                record.getRequestBody(),
                record.getResponseBody(),
                record.getOutcome(),
                record.getLogicalStatus(),
                record.getWireStatus(),
                record.getErrorCode(),
                record.getFailedStep(),
                record.getDurationMs(),
                toTimestamp(record.getStartedAt()),
                toTimestamp(record.getCompletedAt()),
                record.getAppInstance());
    }

    private static Timestamp toTimestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }
}
