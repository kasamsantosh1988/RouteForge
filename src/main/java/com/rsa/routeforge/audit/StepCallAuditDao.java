package com.rsa.routeforge.audit;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;

@Repository
@ConditionalOnProperty(name = "engine.audit.storage-type", havingValue = "db", matchIfMissing = true)
public class StepCallAuditDao implements StepCallAuditStore{

    private static final String INSERT_SQL = """
            INSERT INTO STEP_CALL_AUDIT (
                STEP_AUDIT_ID, AUDIT_ID, TRACE_ID, ROUTE_ID, STEP_ID,
                URI, HTTP_METHOD, HTTP_STATUS,
                REQUEST_HEADERS, REQUEST_BODY_PATH, RESPONSE_BODY_PATH,
                OUTCOME, DURATION_MS, CALLED_AT, APP_INSTANCE
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private final JdbcTemplate jdbcTemplate;

    public StepCallAuditDao(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void insert(StepCallAuditRecord record) {
        jdbcTemplate.update(INSERT_SQL,
                record.getStepAuditId(),
                record.getAuditId(),
                record.getTraceId(),
                record.getRouteId(),
                record.getStepId(),
                record.getUri(),
                record.getHttpMethod(),
                record.getHttpStatus(),
                record.getRequestHeaders(),
                record.getRequestBody(),
                record.getResponseBody(),
                record.getOutcome(),
                record.getDurationMs(),
                toTimestamp(record.getCalledAt()),
                record.getAppInstance());
    }

    private static Timestamp toTimestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }
}