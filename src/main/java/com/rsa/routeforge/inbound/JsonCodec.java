package com.rsa.routeforge.inbound;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsa.routeforge.audit.RouteAuditRecord;
import com.rsa.routeforge.config.EngineProperties;
import com.rsa.routeforge.config.TransactionConfig;
import com.rsa.routeforge.crypto.CipherRegistry;
import com.rsa.routeforge.crypto.EncryptedEnvelope;
import com.rsa.routeforge.crypto.PayloadCipher;
import com.rsa.routeforge.error.ErrorCode;
import com.rsa.routeforge.error.ErrorEnvelopeFactory;
import com.rsa.routeforge.error.OrchestrationException;
import io.micrometer.common.util.StringUtils;
import org.apache.camel.Exchange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static com.rsa.routeforge.inbound.EngineHeaders.CORRELATION_HEADER;
import static com.rsa.routeforge.inbound.EngineHeaders.ENGINE_STATUS_HEADER;

/**
 * The JSON wire format every route has used so far: a JSON object body (optionally inside an
 * {@link EncryptedEnvelope}) merged with request headers, and the {@code uuid / status_code /
 * error / data} envelope on the way back.
 * <p>
 * The parsing and writing code was moved here unchanged from {@code OrchestrationEngine}; the
 * behaviour is pinned by {@code JsonInboundBehaviourTest}.
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class JsonCodec implements InboundCodec {

    private static final Logger log = LoggerFactory.getLogger(JsonCodec.class);

    private static final Set<String> NON_QUERY_HEADERS = Set.of(
            "Accept", "Accept-Encoding", "Accept-Language", "Connection", "Content-Type",
            "Content-Length", "Host", "User-Agent", "Postman-Token", "Cache-Control",
            "CamelHttpMethod", "CamelHttpPath", "CamelHttpQuery", "CamelHttpUri",
            "CamelPlatformHttpContextPath", "CamelHttpResponseCode", "breadcrumbId",
            CORRELATION_HEADER, "Authorization"
    );

    private final ObjectMapper objectMapper;
    private final EngineProperties props;
    private final ErrorEnvelopeFactory envelopes;
    private final CipherRegistry cipherRegistry;

    public JsonCodec(ObjectMapper objectMapper, EngineProperties props,
                     ErrorEnvelopeFactory envelopes, CipherRegistry cipherRegistry) {
        this.objectMapper = objectMapper;
        this.props = props;
        this.envelopes = envelopes;
        this.cipherRegistry = cipherRegistry;
    }

    /** The default format: accepts every route. */
    @Override
    public boolean supports(TransactionConfig cfg) {
        return true;
    }

    // ============================================================
    // Inbound parsing (body + query params)
    // ============================================================

    @Override
    public Map<String, Object> parseRequest(TransactionConfig cfg, Exchange exchange, String raw) {
        Map<String, Object> parsedInput = new HashMap<>();


        if (raw != null && !raw.isBlank()) {
            if (cfg.encryptionProfile() != null) {
                try {
                    EncryptedEnvelope envelope = objectMapper.readValue(raw, EncryptedEnvelope.class);
                    PayloadCipher cipher = cipherRegistry.resolve(cfg.encryptionProfile());
                    if (envelope == null || StringUtils.isBlank(envelope.encryptedData()) || StringUtils.isBlank(envelope.keyId()))
                        throw new OrchestrationException("INVALID_REQUEST", "INVALID ENCRYPTED REQUEST", 400);
                    String decryptedJson = cipher.decrypt(envelope);
                    log.info("Decrypted request body [{}]: {}", cfg.routeId(), decryptedJson);
                    Object bodyParsed;
                    try {
                        bodyParsed = objectMapper.readValue(decryptedJson, Object.class);
                    } catch (Exception e) {
                        throw new OrchestrationException(ErrorCode.MALFORMED_REQUEST_BODY,
                                "Request body is not valid JSON: " + e.getMessage()
                                        + " body=" + truncate(raw), e);
                    }
                    if (!(bodyParsed instanceof Map<?, ?> bodyMap)) {
                        throw new OrchestrationException(ErrorCode.MALFORMED_REQUEST_BODY,
                                "Request body must be a JSON object but was "
                                        + (bodyParsed == null ? "null" : bodyParsed.getClass().getSimpleName())
                                        + " body=" + truncate(raw));
                    }
                    bodyMap.forEach((k, v) -> parsedInput.put(String.valueOf(k), v));
                } catch (Exception e) {
                    log.warn("No/invalid JSON body for [{}], continuing with query params only: {}",
                            cfg.routeId(), e.getMessage());
                    throw new OrchestrationException("INVALID_REQUEST", "INVALID ENCRYPTED REQUEST", 400);
                }
            } else {
                try {
                    Object bodyParsed;
                    try {
                        bodyParsed = objectMapper.readValue(raw, Object.class);
                    } catch (Exception e) {
                        throw new OrchestrationException(ErrorCode.MALFORMED_REQUEST_BODY,
                                "Request body is not valid JSON: " + e.getMessage()
                                        + " body=" + truncate(raw), e);
                    }
                    if (!(bodyParsed instanceof Map<?, ?> bodyMap)) {
                        throw new OrchestrationException(ErrorCode.MALFORMED_REQUEST_BODY,
                                "Request body must be a JSON object but was "
                                        + (bodyParsed == null ? "null" : bodyParsed.getClass().getSimpleName())
                                        + " body=" + truncate(raw));
                    }
                    bodyMap.forEach((k, v) -> parsedInput.put(String.valueOf(k), v));
                } catch (Exception e) {
                    log.warn("No/invalid JSON body for [{}], continuing with query params only: {}",
                            cfg.routeId(), e.getMessage());
                    throw new OrchestrationException("INVALID_REQUEST", "INVALID  REQUEST", 400);
                }
            }
        }


        Map<String, Object> headers = exchange.getIn().getHeaders();
        headers.forEach((key, value) -> {
            if (!NON_QUERY_HEADERS.contains(key)) {
                parsedInput.putIfAbsent(key, value);
            }
        });

        log.info("Merged incoming request (body + query) [{}]: {}", cfg.routeId(), parsedInput);
        return parsedInput;
    }

    // ============================================================
    // Response writing
    // ============================================================

    @Override
    public void writeSuccess(Exchange exchange, TransactionConfig cfg, Object body, String uuid,
                             String traceId, RouteAuditRecord audit) {
        writeResponse(exchange, envelopes.success(body, uuid), 200, cfg, traceId, audit);
    }

    @Override
    public void writeError(Exchange exchange, TransactionConfig cfg, OrchestrationException error,
                           String traceId, String failedStep, String uuid, RouteAuditRecord audit) {
        writeResponse(exchange, envelopes.error(error, traceId, failedStep, uuid),
                error.getHttpStatus(), cfg, traceId, audit);
    }

    /**
     * Writes the envelope.
     * <p>
     * The wire status is 200 for every outcome by default (see {@code engine.http.propagate-status}),
     * so the true status is always published as {@value EngineHeaders#ENGINE_STATUS_HEADER} and as a metric.
     */
    private void writeResponse(Exchange exchange, Map<String, Object> envelope,
                               int logicalStatus, TransactionConfig cfg, String traceId,
                               RouteAuditRecord audit) {
        int wireStatus = props.isAlways200(cfg.routeId()) ? 200 : logicalStatus;
        if(audit!=null)
        envelope.put("uuid", audit.getAuditId());
        String json = envelopes.toJson(envelope);
        if (cfg.encryptionProfile() != null) {
            String plaintextJson = toJsonSafely(envelope);
            PayloadCipher cipher = cipherRegistry.resolve(cfg.encryptionProfile());
            EncryptedEnvelope encrypted = cipher.encrypt(plaintextJson);
            exchange.getIn().setBody(toJsonSafely(encrypted));
            log.info("Response envelope before serialization = {}", envelope);
        } else {
            exchange.getIn().setBody(toJsonSafely(envelope));
            log.info("Response envelope before serialization = {}", envelope);
        }
        exchange.getIn().setHeader(Exchange.CONTENT_TYPE, "application/json");
        exchange.getIn().setHeader("Accept", "application/json");
        exchange.getIn().setHeader(Exchange.HTTP_RESPONSE_CODE, wireStatus);
        exchange.getIn().setHeader(ENGINE_STATUS_HEADER, logicalStatus);
        exchange.getIn().setHeader(CORRELATION_HEADER, traceId);

        // Captured here rather than reconstructed later: this is the exact byte sequence the caller
        // receives, including the fallback payload if serialization failed.
        if (audit != null) {
            audit.setResponseBody(json);
            audit.setLogicalStatus(logicalStatus);
            audit.setWireStatus(wireStatus);
        }
    }

    // ============================================================
    // Helpers
    // ============================================================

    private String toJsonSafely(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            throw new OrchestrationException(ErrorCode.SERIALIZATION_FAILED,
                    "Failed to serialize request body: " + e.getMessage(), e);
        }
    }

    private String truncate(String value) {
        int max = props.getDownstream().getMaxLogBodyChars();
        if (value == null || value.length() <= max) return value;
        return value.substring(0, max) + "...<truncated>";
    }
}
