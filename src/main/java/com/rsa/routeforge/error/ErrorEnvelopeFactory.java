package com.rsa.routeforge.error;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The one place the response envelope is built and serialized.
 * <p>
 * The existing keys — {@code status_code}, {@code error}, {@code data} — keep their meaning and
 * position, so current partners are unaffected. Everything added inside {@code error} is additive.
 */
@Component
public class ErrorEnvelopeFactory {

    private static final Logger log = LoggerFactory.getLogger(ErrorEnvelopeFactory.class);

    /**
     * Pre-serialized so the error path cannot itself fail. Without this, a serialization failure
     * inside the boundary's catch block escapes to Camel, which writes a Java stack trace to the
     * caller as {@code text/plain}.
     */
    public static final String FALLBACK_ERROR_JSON =
            "{\"status_code\":\"500\","
                    + "\"error\":{\"code\":\"SERIALIZATION_FAILED\","
                    + "\"message\":\"An unexpected error occurred.\","
                    + "\"details\":[]},"
                    + "\"data\":null}";

    private final ObjectMapper objectMapper;

    public ErrorEnvelopeFactory(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> success(Object data,String uuid) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", "");
        error.put("message", "");
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("uuid", uuid);
        envelope.put("status_code", "200");
        envelope.put("error", error);
        envelope.put("data", data);
        
        return envelope;
    }

    public Map<String, Object> error(OrchestrationException oe, String traceId, String stepId,String uuid) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", oe.getCode());
        error.put("message", oe.getMessage());   // client-safe by the OrchestrationException invariant
        error.put("traceId", traceId);
        error.put("step", stepId);
        error.put("details", oe.details().stream()
                .map(ErrorEnvelopeFactory::renderDetail)
                .toList());

        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("uuid", uuid);
        envelope.put("status_code", String.valueOf(oe.getHttpStatus()));
        envelope.put("error", error);
        envelope.put("data", null);
       
        return envelope;
    }

    private static Map<String, Object> renderDetail(FieldError fe) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("field", fe.field());
        m.put("reason", fe.reason());
        return m;
    }

    /**
     * Serializes an envelope. Never throws — on failure it returns {@link #FALLBACK_ERROR_JSON},
     * because this is called from inside the boundary's catch blocks.
     */
    public String toJson(Map<String, Object> envelope) {
        try {
            return objectMapper.writeValueAsString(envelope);
        } catch (Exception e) {
            log.error("Envelope serialization failed; returning fallback error payload", e);
            return FALLBACK_ERROR_JSON;
        }
    }

    /** Convenience for callers that only have field errors, e.g. the admin advice. */
    public Map<String, Object> validationError(List<FieldError> details, String traceId) {
        return error(ValidationException.of(details), traceId, null,"");
    }
}
