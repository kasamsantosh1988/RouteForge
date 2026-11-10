package com.rsa.routeforge.inbound;

import com.rsa.routeforge.audit.RouteAuditRecord;
import com.rsa.routeforge.config.TransactionConfig;
import com.rsa.routeforge.error.OrchestrationException;
import org.apache.camel.Exchange;

import java.util.Map;

/**
 * The wire format of a route: how its request body becomes the engine's input map, and how the
 * outcome is written back to the caller.
 * <p>
 * The engine owns everything in between (OpenAPI check, request JOLT, partner, uuid, steps,
 * response JOLT, audit, metrics) and is the same for every format. A codec only translates at the
 * edges, so adding a format such as SOAP never touches the step pipeline.
 * <p>
 * Implementations are Spring beans, consulted in {@code @Order} order; the first whose
 * {@link #supports} accepts the route is used. {@link JsonCodec} accepts every route and is
 * ordered last, so it remains the default.
 */
public interface InboundCodec {

    /** Whether this codec handles the given route. */
    boolean supports(TransactionConfig cfg);

    /**
     * Turns the raw request into the input map that becomes {@code ctx.rawInput}.
     *
     * @param rawBody the body as already read by the engine (it is read once, so it can be audited
     *                even when parsing fails)
     * @throws OrchestrationException for a request the caller got wrong; the engine writes it back
     *                                through {@link #writeError}
     */
    Map<String, Object> parseRequest(TransactionConfig cfg, Exchange exchange, String rawBody);

    /**
     * Writes a successful outcome.
     *
     * @param body the response body after response JOLT (or the whole context when the route has none)
     */
    void writeSuccess(Exchange exchange, TransactionConfig cfg, Object body, String uuid,
                      String traceId, RouteAuditRecord audit);

    /**
     * Writes a failed outcome. Called from the engine's error boundary: anything thrown here
     * escapes the engine and is handled only by the route's Camel safety net, so implementations
     * should not throw.
     */
    void writeError(Exchange exchange, TransactionConfig cfg, OrchestrationException error,
                    String traceId, String failedStep, String uuid, RouteAuditRecord audit);
}
