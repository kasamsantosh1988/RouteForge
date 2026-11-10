package com.rsa.routeforge.soap;

import com.rsa.routeforge.audit.RouteAuditRecord;
import com.rsa.routeforge.config.EngineMetrics;
import com.rsa.routeforge.config.TransactionConfig;
import com.rsa.routeforge.error.ErrorCode;
import com.rsa.routeforge.error.OrchestrationException;
import com.rsa.routeforge.inbound.InboundCodec;
import com.rsa.routeforge.route.RouteForgeProperties;
import org.apache.camel.Exchange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

import static com.rsa.routeforge.inbound.EngineHeaders.CORRELATION_HEADER;
import static com.rsa.routeforge.inbound.EngineHeaders.ENGINE_STATUS_HEADER;

/**
 * The SOAP 1.1 wire format for routes with {@code "channel": "soap"}.
 * <p>
 * <b>Request:</b> the configured {@code soap.fields} are read from the operation element into
 * {@code ctx.input}, and the caller is authenticated before any step runs. The password field is
 * removed from the input once checked, so it cannot reach a step, a downstream call, a response or
 * a log line.
 * <p>
 * <b>Success:</b> HTTP 200 with the configured wrapper. The return value is {@code soapReturn}
 * from the response body (the context, or the output of the route's response JOLT); a plain string
 * or number body is used as is. Without a {@code soapReturn} the return element is empty — the
 * context itself is never sent to a SOAP partner.
 * <p>
 * <b>Failure:</b> HTTP 500 with a SOAP 1.1 fault, as SOAP 1.1 requires: {@code Client} for errors
 * the caller caused, {@code Server} otherwise. The fault string is the error's client-safe message.
 */
@Component
@Order(100)
public class SoapCodec implements InboundCodec {

    private static final Logger log = LoggerFactory.getLogger(SoapCodec.class);

    /** Key a step or response JOLT sets to the string returned to the partner. */
    public static final String RETURN_KEY = "soapReturn";

    private static final String CONTENT_TYPE = "text/xml; charset=utf-8";

    private final RouteForgeProperties properties;
    private final ObjectProvider<CredentialVerifier> verifier;
    private final EngineMetrics metrics;

    public SoapCodec(RouteForgeProperties properties, ObjectProvider<CredentialVerifier> verifier,
                     EngineMetrics metrics) {
        this.properties = properties;
        this.verifier = verifier;
        this.metrics = metrics;
    }

    @Override
    public boolean supports(TransactionConfig cfg) {
        return cfg.isSoap();
    }

    @Override
    public Map<String, Object> parseRequest(TransactionConfig cfg, Exchange exchange, String rawBody) {
        OrchestrationException dispatchError = exchange.getProperty(SoapExchange.DISPATCH_ERROR, OrchestrationException.class);
        if (dispatchError != null) {
            throw dispatchError;
        }
        SoapRequest dispatched = exchange.getProperty(SoapExchange.REQUEST, SoapRequest.class);
        SoapRequest request = dispatched != null
                ? dispatched
                : SoapRequest.parse(rawBody, properties.getSoap().limits());

        SoapRouteConfig soap = cfg.soap();
        Map<String, Object> input = new LinkedHashMap<>();
        soap.fieldsOrEmpty().forEach((name, path) ->
                input.put(name, request.field(path, soap.namespacesOrEmpty(), soap.lenient())));

        authenticate(cfg, soap, input, exchange);
        log.info("SOAP operation {} [{}]: {} field(s)", request.operationName(), cfg.routeId(), input.size());
        return input;
    }

    private void authenticate(TransactionConfig cfg, SoapRouteConfig soap, Map<String, Object> input, Exchange exchange) {
        switch (soap.authType()) {
            case SoapAuthConfig.BODY_CREDENTIALS -> {
                SoapAuthConfig auth = soap.auth();
                Object user = input.get(auth.user());
                Object password = input.remove(auth.password());
                CredentialVerifier check = verifier.getIfAvailable();
                if (check == null) {
                    throw new OrchestrationException(ErrorCode.CONFIG_ERROR,
                            "route '" + cfg.routeId() + "' uses bodyCredentials but no CredentialVerifier bean exists");
                }
                boolean valid;
                try {
                    valid = check.verify(cfg.partnerCode(), asString(user), asString(password));
                } catch (RuntimeException e) {
                    throw new OrchestrationException(ErrorCode.INTERNAL_SERVER_ERROR,
                            "CredentialVerifier failed for route '" + cfg.routeId() + "': " + e.getClass().getSimpleName(), e);
                }
                if (!valid) {
                    throw new OrchestrationException(ErrorCode.AUTHENTICATION_FAILED,
                            "invalid credentials for partner '" + cfg.partnerCode() + "', user '" + user + "'");
                }
            }
            case SoapAuthConfig.GATEWAY -> {
                String header = soap.auth() == null ? null : soap.auth().header();
                if (header != null) {
                    String value = exchange.getIn().getHeader(header, String.class);
                    if (value == null || value.isBlank()) {
                        throw new OrchestrationException(ErrorCode.AUTHENTICATION_FAILED,
                                "gateway header '" + header + "' missing");
                    }
                }
            }
            default -> {
                // none
            }
        }
    }

    @Override
    public void writeSuccess(Exchange exchange, TransactionConfig cfg, Object body, String uuid,
                             String traceId, RouteAuditRecord audit) {
        SoapRouteConfig soap = cfg.soap();
        String xml = SoapResponseWriter.success(soap.response(), soap.namespacesOrEmpty(), returnValue(body, cfg));
        write(exchange, xml, 200, 200, traceId, audit);
    }

    @Override
    public void writeError(Exchange exchange, TransactionConfig cfg, OrchestrationException error,
                           String traceId, String failedStep, String uuid, RouteAuditRecord audit) {
        String xml;
        String faultCode = "Server";
        try {
            faultCode = SoapResponseWriter.faultCodeFor(error.getCode(), error.getHttpStatus());
            xml = SoapResponseWriter.fault(faultCode, error.getMessage(), error.getCode(), traceId);
        } catch (Throwable t) {
            xml = SoapResponseWriter.FALLBACK_FAULT;
        }
        try {
            metrics.recordSoapFault(cfg.routeId(), faultCode, error.getCode());
        } catch (Throwable t) {
            log.debug("SOAP fault metric not recorded", t);
        }
        write(exchange, xml, error.getHttpStatus(), 500, traceId, audit);
    }

    private static void write(Exchange exchange, String xml, int logicalStatus, int wireStatus,
                              String traceId, RouteAuditRecord audit) {
        exchange.getIn().setBody(xml);
        exchange.getIn().setHeader(Exchange.CONTENT_TYPE, CONTENT_TYPE);
        exchange.getIn().setHeader(Exchange.HTTP_RESPONSE_CODE, wireStatus);
        exchange.getIn().setHeader(ENGINE_STATUS_HEADER, logicalStatus);
        exchange.getIn().setHeader(CORRELATION_HEADER, traceId);
        if (audit != null) {
            audit.setResponseBody(xml);
            audit.setLogicalStatus(logicalStatus);
            audit.setWireStatus(wireStatus);
        }
    }

    private static String returnValue(Object body, TransactionConfig cfg) {
        if (body instanceof Map<?, ?> map) {
            if (map.containsKey(RETURN_KEY)) {
                return asString(map.get(RETURN_KEY));
            }
            log.warn("SOAP route '{}' produced no '{}'; returning an empty {}", cfg.routeId(), RETURN_KEY,
                    cfg.soap().response().returnElement());
            return null;
        }
        if (body instanceof CharSequence || body instanceof Number || body instanceof Boolean) {
            return body.toString();
        }
        if (body != null) {
            log.warn("SOAP route '{}' produced a {} instead of '{}'; returning an empty {}", cfg.routeId(),
                    body.getClass().getSimpleName(), RETURN_KEY, cfg.soap().response().returnElement());
        }
        return null;
    }

    private static String asString(Object value) {
        return value == null ? null : value.toString();
    }
}
