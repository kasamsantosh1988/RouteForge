package com.rsa.routeforge.error;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponse;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;
import java.util.UUID;

/**
 * Renders Spring MVC controller failures — currently the {@code /admin/routes} endpoints — using
 * the same envelope the engine produces.
 * <p>
 * Without this, an admin failure returns Spring's default error JSON, a completely different shape
 * from every other response the service emits.
 */
@RestControllerAdvice
public class GlobalExceptionAdvice {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionAdvice.class);

    private final ErrorEnvelopeFactory envelopes;
    private final ExceptionTranslator translator;

    public GlobalExceptionAdvice(ErrorEnvelopeFactory envelopes, ExceptionTranslator translator) {
        this.envelopes = envelopes;
        this.translator = translator;
    }

    @ExceptionHandler(Throwable.class)
    public ResponseEntity<Map<String, Object>> handle(Throwable t) {
        OrchestrationException oe = translator.translate(t);
        String traceId = traceId();

        if (oe.getHttpStatus() >= 500) {
            log.error("Admin request failed [{}] traceId={} — {}", oe.getCode(), traceId, oe.internalDetail(), t);
        } else {
            log.warn("Admin request failed [{}] traceId={} — {}", oe.getCode(), traceId, oe.internalDetail());
        }

        // Admin surfaces are internal, so they always report the real status — the always-200
        // partner contract does not apply here.
        return ResponseEntity.status(oe.getHttpStatus())
                .header("X-Correlation-Id", traceId)
                .body(envelopes.error(oe, traceId, null,""));
    }

    private static String traceId() {
        String existing = MDC.get("traceId");
        return existing != null ? existing : UUID.randomUUID().toString();
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethodNotSupported(
            HttpRequestMethodNotSupportedException ex,
            HttpServletRequest request) {

        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body((ErrorResponse) Map.of(
                        "status", 405,
                        "message", "Method not supported"));
    }
}
