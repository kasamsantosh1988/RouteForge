package com.rsa.routeforge.audit;


import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.SimpleStatement;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.time.Instant;

@Repository
@ConditionalOnProperty(name = "engine.audit.storage-type", havingValue = "cassandra")
public class CassandraStepCallAuditStore implements StepCallAuditStore {

    private final CqlSession session;

    public CassandraStepCallAuditStore(CqlSession session) {
        this.session = session;
    }

    @Override
    public void insert(StepCallAuditRecord record) {
        session.execute(SimpleStatement.newInstance("""
                INSERT INTO STEP_CALL_AUDIT (
                STEP_AUDIT_ID, AUDIT_ID, TRACE_ID, ROUTE_ID, STEP_ID,
                URI, HTTP_METHOD, HTTP_STATUS,
                REQUEST_HEADERS, REQUEST_BODY, RESPONSE_BODY,
                OUTCOME, DURATION_MS, CALLED_AT, APP_INSTANCE
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
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
                toInstant(record.getCalledAt()),
                record.getAppInstance()));
    }

    private static Instant toInstant(Instant i) { return i; }
}
