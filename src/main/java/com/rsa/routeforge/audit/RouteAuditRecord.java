package com.rsa.routeforge.audit;

import lombok.Data;

import java.time.Instant;

/**
 * One inbound request and the response it received — the in-memory form of a {@code ROUTE_AUDIT} row.
 * <p>
 * A mutable class rather than a record because it is filled in across the request lifecycle: route
 * identity and the raw request at entry, the response and outcome at exit. A record would force the
 * engine to thread a dozen locals through to a single construction site at the very end, which is
 * precisely the path a thrown exception skips.
 * <p>
 * <strong>Confined to one thread.</strong> Exactly one request thread writes this object, and it is
 * handed to {@link RouteAuditWriter} only once, at the end of the request. Nothing else may retain
 * a reference — if step-level auditing is added later and branch threads start contributing, that
 * assumption has to be revisited.
 */
@Data
public class RouteAuditRecord {

    public static final String OUTCOME_SUCCESS = "SUCCESS";
    public static final String OUTCOME_FAILURE = "FAILURE";

    private String auditId;
    private String traceId;
    private String routeId;
    private String routePath;
    private String httpMethod;
    private String queryString;
    private String requestHeaders;
    private String requestBody;
    private String responseBody;
    private String outcome;
    private Integer logicalStatus;
    private Integer wireStatus;
    private String errorCode;
    private String failedStep;
    private long durationMs;
    private Instant startedAt;
    private Instant completedAt;
    private String appInstance;
    private String partnerCode;
}
