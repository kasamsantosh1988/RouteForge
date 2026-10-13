package com.rsa.routeforge.audit;


import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.SimpleStatement;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.time.Instant;

@Repository
@ConditionalOnProperty(name = "engine.audit.storage-type", havingValue = "cassandra")
public class CassandraRouteAuditStore implements RouteAuditStore {

    private final CqlSession session;

    public CassandraRouteAuditStore(CqlSession session) {
        this.session = session;
    }

    @Override
    public void insert(RouteAuditRecord record) {
        session.execute(SimpleStatement.newInstance("""
                INSERT INTO route_audit (
                    audit_id, trace_id, route_id, route_path, http_method, query_string,
                    request_headers, request_body, response_body, outcome, logical_status,
                    wire_status, error_code, failed_step, duration_ms, started_at, completed_at, app_instance
                ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,
                record.getAuditId(), record.getTraceId(), record.getRouteId(), record.getRoutePath(),
                record.getHttpMethod(), record.getQueryString(), record.getRequestHeaders(),
                record.getRequestBody(), record.getResponseBody(), record.getOutcome(),
                record.getLogicalStatus(), record.getWireStatus(), record.getErrorCode(),
                record.getFailedStep(), record.getDurationMs(),
                toInstant(record.getStartedAt()), toInstant(record.getCompletedAt()), record.getAppInstance()));
    }

    private static Instant toInstant(Instant i) { return i; }
}
