package com.rsa.routeforge.error;

/**
 * Catalogue of every error the engine can report.
 * <p>
 * Each member owns three things: the logical HTTP status it maps to, a <em>client-safe</em> message,
 * and whether retrying could plausibly help. Client messages are static text — never build one by
 * concatenating an exception message, or internal detail leaks to the partner.
 */
public enum ErrorCode {

    // ---- Client-caused (4xx) -------------------------------------------------
    VALIDATION_FAILED(400, "One or more request fields are invalid."),
    MALFORMED_REQUEST_BODY(400, "Request body is not valid JSON."),

    // ---- Downstream (5xx) ----------------------------------------------------
    DOWNSTREAM_TIMEOUT(504, "An upstream service did not respond in time."),
    DOWNSTREAM_UNAVAILABLE(502, "An upstream service is unavailable."),
    DOWNSTREAM_TLS_ERROR(502, "An upstream service is unavailable."),
    DOWNSTREAM_REJECTED(502, "An upstream service rejected the request."),
    DOWNSTREAM_NOT_FOUND(502, "A required record was not found in an upstream service."),
    // Deliberately vague to the client: an auth failure is our credential problem, not theirs.
    DOWNSTREAM_AUTH_FAILED(502, "An upstream service is unavailable."),
    DOWNSTREAM_RATE_LIMITED(503, "Service is busy. Please retry shortly."),
    DOWNSTREAM_ERROR(502, "An upstream service returned an error."),
    DOWNSTREAM_INVALID_RESPONSE(502, "An upstream service returned an unexpected response."),
    DOWNSTREAM_EMPTY_RESPONSE(502, "An upstream service returned an empty response."),
    DOWNSTREAM_CALL_FAILED(502, "An upstream service call failed."),
    DOWNSTREAM_CIRCUIT_OPEN(503, "Service temporarily unavailable. Please retry shortly."),

    // ---- Engine / configuration (5xx) — all report the same opaque message ---
    CONFIG_ERROR(500, "An unexpected error occurred."),
    UNKNOWN_STEP_TYPE(500, "An unexpected error occurred."),
    BEAN_NOT_FOUND(500, "An unexpected error occurred."),
    INVALID_BEAN_TYPE(500, "An unexpected error occurred."),
    INVALID_CONDITION(500, "An unexpected error occurred."),
    URI_PLACEHOLDER_UNRESOLVED(500, "An unexpected error occurred."),
    TRANSFORM_FAILED(500, "An unexpected error occurred."),
    VALIDATOR_FAILED(500, "An unexpected error occurred."),
    PARALLEL_STEP_FAILED(500, "An unexpected error occurred."),
    PARALLEL_TIMEOUT(504, "The request took too long to complete."),
    SERIALIZATION_FAILED(500, "An unexpected error occurred."),
    INTERNAL_SERVER_ERROR(500, "An unexpected error occurred.");

    private final int defaultHttpStatus;
    private final String clientMessage;

    ErrorCode(int defaultHttpStatus, String clientMessage) {
        this.defaultHttpStatus = defaultHttpStatus;
        this.clientMessage = clientMessage;
    }

    /** The wire code, e.g. {@code DOWNSTREAM_TIMEOUT}. */
    public String code() {
        return name();
    }

    public int defaultHttpStatus() {
        return defaultHttpStatus;
    }

    /** Safe to return to a partner. Contains no internal detail by construction. */
    public String clientMessage() {
        return clientMessage;
    }

}
