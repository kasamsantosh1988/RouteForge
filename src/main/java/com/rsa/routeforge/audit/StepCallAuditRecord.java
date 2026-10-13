package com.rsa.routeforge.audit;

import lombok.Data;

import java.time.Instant;

/**
 * One downstream call made from a {@code call} step — the in-memory form of a
 * {@code STEP_CALL_AUDIT} row.
 * <p>
 * Unlike {@link RouteAuditRecord}, which is confined to one request thread for its whole
 * lifetime, an instance of this class is built and handed off in a single call inside
 * {@code executeCall} — never mutated afterwards, never shared. That is what makes it safe when
 * {@code executeCall} runs on a branch thread inside a {@code parallel} step: each branch
 * constructs and submits its own instance, so there is nothing to race on.
 */
@Data
public class StepCallAuditRecord {

    public static final String OUTCOME_SUCCESS = "SUCCESS";
    public static final String OUTCOME_FAILURE = "FAILURE";

    private String stepAuditId;
    private String auditId;      // links back to the parent ROUTE_AUDIT row
    private String traceId;
    private String routeId;
    private String stepId;
    private String uri;
    private String httpMethod;
    private Integer httpStatus;
    private String requestHeaders;
    private String requestBody;
    private String responseBody;
    private String outcome;
    private long durationMs;
    private Instant calledAt;
    private String appInstance;
    private String partnerCode;

}