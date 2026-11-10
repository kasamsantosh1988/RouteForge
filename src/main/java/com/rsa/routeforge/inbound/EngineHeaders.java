package com.rsa.routeforge.inbound;

/**
 * HTTP header names the engine reads and writes on every route, whatever the inbound format.
 */
public final class EngineHeaders {

    /** Correlation id: read from the request when present, echoed on the response and sent downstream. */
    public static final String CORRELATION_HEADER = "X-Correlation-Id";

    /** The logical status of the outcome, published because the wire status is often forced to 200. */
    public static final String ENGINE_STATUS_HEADER = "X-Engine-Status";

    private EngineHeaders() {
    }
}
