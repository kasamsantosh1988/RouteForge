package com.rsa.routeforge.config;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.model.Request;
import com.atlassian.oai.validator.model.SimpleRequest;
import com.atlassian.oai.validator.report.ValidationReport;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsa.routeforge.audit.*;
import com.rsa.routeforge.error.*;
import com.rsa.routeforge.auth.AuthConfig;
import com.rsa.routeforge.auth.AuthTokenProvider;
import com.rsa.routeforge.crypto.CipherRegistry;
import com.rsa.routeforge.crypto.EncryptedEnvelope;
import com.rsa.routeforge.crypto.PayloadCipher;
import com.rsa.routeforge.openapi.OpenApiValidationConfig;
import com.rsa.routeforge.openapi.OpenApiValidatorRegistry;
import com.rsa.routeforge.partner.PartnerResolverService;
import com.rsa.routeforge.validation.ConditionalRequiredValidator;
import com.rsa.routeforge.validation.FieldValidator;
import com.rsa.routeforge.validation.ValidationError;
import com.rsa.routeforge.validation.ValidationRule;
import io.micrometer.common.util.StringUtils;
import org.apache.camel.Exchange;
import org.apache.camel.ProducerTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContext;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.MapAccessor;
import org.springframework.expression.spel.support.SimpleEvaluationContext;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@ConditionalOnProperty(prefix = "routeforge", name = "enabled", havingValue = "true", matchIfMissing = true)
public class OrchestrationEngine {

    private static final Logger log = LoggerFactory.getLogger(OrchestrationEngine.class);
    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\{\\{([^}]+)\\}\\}");

    private static final String CORRELATION_HEADER = "X-Correlation-Id";
    private static final String ENGINE_STATUS_HEADER = "X-Engine-Status";

    /**
     * Methods for which a request body is not sent — some servers reject a GET with a body.
     */
    private static final Set<String> BODYLESS_METHODS = Set.of("GET", "DELETE", "HEAD", "OPTIONS");

    private static final Set<String> NON_QUERY_HEADERS = Set.of(
            "Accept", "Accept-Encoding", "Accept-Language", "Connection", "Content-Type",
            "Content-Length", "Host", "User-Agent", "Postman-Token", "Cache-Control",
            "CamelHttpMethod", "CamelHttpPath", "CamelHttpQuery", "CamelHttpUri",
            "CamelPlatformHttpContextPath", "CamelHttpResponseCode", "breadcrumbId",
            CORRELATION_HEADER, "Authorization"
    );

    private final ProducerTemplate producerTemplate;
    private final ApplicationContext applicationContext;
    private final JoltTransformer joltTransformer;
    private final ObjectMapper objectMapper;
    private final EngineProperties props;
    private final ExceptionTranslator translator;
    private final ErrorEnvelopeFactory envelopes;
    private final EngineMetrics metrics;
    private final RouteAuditWriter auditWriter;
    private final AuditPayloadSanitizer auditSanitizer;
    private final ExecutorService parallelExecutor;
    private final CipherRegistry cipherRegistry;
    private final AuthTokenProvider authTokenProvider;
    private final StepCallAuditWriter stepCallAuditWriter;
    private final PartnerResolverService partnerResolverService;
    private final OpenApiValidatorRegistry openApiValidatorRegistry;

    public OrchestrationEngine(ExceptionTranslator translator, ProducerTemplate producerTemplate, ApplicationContext applicationContext, JoltTransformer joltTransformer, ObjectMapper objectMapper, EngineProperties props, ErrorEnvelopeFactory envelopes, EngineMetrics metrics, StepCallAuditWriter stepCallAuditWriter, RouteAuditWriter auditWriter, AuditPayloadSanitizer auditSanitizer, CipherRegistry cipherRegistry, AuthTokenProvider authTokenProvider, @Qualifier("engineParallelExecutor") ExecutorService parallelExecutor, PartnerResolverService partnerResolverService, OpenApiValidatorRegistry openApiValidatorRegistry) {

        this.translator = translator;
        this.producerTemplate = producerTemplate;
        this.applicationContext = applicationContext;
        this.joltTransformer = joltTransformer;
        this.objectMapper = objectMapper;
        this.props = props;
        this.envelopes = envelopes;
        this.metrics = metrics;
        this.stepCallAuditWriter = stepCallAuditWriter;
        this.auditWriter = auditWriter;
        this.auditSanitizer = auditSanitizer;
        this.cipherRegistry = cipherRegistry;
        this.authTokenProvider = authTokenProvider;
        this.parallelExecutor = parallelExecutor;
        this.partnerResolverService = partnerResolverService;
        this.openApiValidatorRegistry = openApiValidatorRegistry;
    }
    // ============================================================
    // Entry point — the single error boundary
    // ============================================================

    public void execute(TransactionConfig cfg, Exchange exchange) {
        long startTime = System.currentTimeMillis();

        // Correlation is established here rather than in a servlet filter: Camel's Spring-Boot
        // platform-http consumer dispatches via CompletableFuture.runAsync(), so route processing
        // runs on a different thread from the one a filter would see.
        Map<String, String> priorMdc = MDC.getCopyOfContextMap();
        String traceId = resolveTraceId(exchange);
        MDC.put("traceId", traceId);
        MDC.put("routeId", cfg.routeId());

        // Built before the try so the record exists for every outcome, including one thrown by the
        // very first statement inside it. newAuditRecord absorbs its own failures for that reason.
        RouteAuditRecord audit = newAuditRecord(cfg, exchange, traceId, startTime);
        String uuid = "";
        try {
            log.info(">>> Route START");

            // Read once, here rather than inside parseIncoming, so a body too malformed to parse is
            // still on the audit row when the 400 goes out — that payload is the whole point of the
            // audit for a rejected request.
            String rawBody = exchange.getIn().getBody(String.class);
            if (audit != null) {
                audit.setRequestBody(rawBody);
            }

            Map<String, Object> ctx = new HashMap<>();
            Map<String, Object> parsedInput = parseIncoming(cfg, exchange, rawBody);
            ctx.put("rawInput", parsedInput);
            if (cfg.openApiValidation() != null)
                openApiValidation(cfg.openApiValidation(), ctx, exchange);
            ctx.put("input", cfg.requestJoltSpec() != null
                    ? joltTransformer.transform(cfg.requestJoltSpec(), parsedInput)
                    : parsedInput);
            ctx.put("partnerCode", cfg.partnerCode());
            if (cfg.partnerCode() != null) {
                Long partnerId = partnerResolverService.getPartnerIdByPartnerCode(cfg.partnerCode());
                ctx.put("partnerId", partnerId);
            }

            uuid = resolveUUID(ctx);
            if (audit != null) {
                audit.setAuditId(uuid);
                audit.setPartnerCode(cfg.partnerCode());
            }
            ctx.put("uuid", uuid);


            log.info("ctx.input built with {} field(s)", sizeOf(ctx.get("input")));
            logPayload("ctx.input: {}", ctx.get("input"));

            executeSteps(cfg.steps(), ctx, cfg.routeId(), exchange);

            logPayload("Full ctx after all steps: {}", ctx);

            String responseSpec = resolveResponseJoltSpec(cfg, ctx);

            Object finalBody = responseSpec != null ? joltTransformer.transform(responseSpec, ctx) : ctx;

            log.info("Final response after JOLT = {}", finalBody);

            if (audit != null) {
                audit.setOutcome(RouteAuditRecord.OUTCOME_SUCCESS);
            }
            writeResponse(exchange, envelopes.success(finalBody, uuid), 200, cfg, traceId, audit);
            metrics.recordSuccess(cfg.routeId(), startTime);
            log.info("<<< Route SUCCESS in {} ms", System.currentTimeMillis() - startTime);

        } catch (Throwable t) {
            // Throwable, not Exception: nothing may escape this method. If it did, Camel's
            // DefaultHttpBinding would write a raw Java stack trace to the caller as text/plain.
            OrchestrationException oe = translator.translate(t);
            String failedStep = MDC.get("stepId");
            long elapsed = System.currentTimeMillis() - startTime;

            if (oe.getHttpStatus() >= 500) {
                log.error("<<< Route FAILED [{}] in {} ms at step '{}' — {}",
                        oe.getCode(), elapsed, failedStep, oe.internalDetail(), t);
            } else {
                // Client-caused: WARN, so a partner sending bad input never pages anyone.
                log.warn("<<< Route FAILED [{}] in {} ms at step '{}' — {}",
                        oe.getCode(), elapsed, failedStep, oe.internalDetail());
            }

            if (audit != null) {
                audit.setOutcome(RouteAuditRecord.OUTCOME_FAILURE);
                audit.setErrorCode(oe.getCode());
                audit.setFailedStep(failedStep);
            }

            metrics.recordFailure(cfg.routeId(), oe.getCode(), oe.getHttpStatus(), startTime);
            writeResponse(exchange, envelopes.error(oe, traceId, failedStep, uuid),
                    oe.getHttpStatus(), cfg, traceId, audit);

        } finally {
            try {
                // Before the MDC is restored, so anything the writer logs still carries this
                // request's traceId.
                if (audit != null) {
                    audit.setDurationMs(System.currentTimeMillis() - startTime);
                    audit.setCompletedAt(Instant.now());
                    auditWriter.write(audit);
                }
            } catch (Throwable t) {
                // RouteAuditWriter.write is contracted never to throw, and catches Throwable to
                // honour that. This is the guard for the day the contract is broken: an exception
                // raised in a finally block escapes execute() entirely, discarding the response
                // already written to the exchange and skipping the MDC restore below.
                log.error("Audit flush threw despite its never-throws contract", t);
            } finally {
                if (priorMdc != null) {
                    MDC.setContextMap(priorMdc);
                } else {
                    MDC.clear();
                }
            }
        }
    }

    /**
     * Seeds the audit row with everything known before the request is touched.
     * <p>
     * The HTTP method, URI and query come from exchange <em>properties</em> set by
     * {@code MarketplaceRoutesBuilder.captureInboundHttpMetadata} — the corresponding
     * {@code CamelHttp*} headers have already been stripped by the time the engine runs.
     * <p>
     * Absorbs its own failures and returns {@code null} rather than propagating. It is called from
     * outside the boundary's {@code try}, where a thrown exception would escape {@code execute()}
     * and reach Camel — the one failure mode the whole error subsystem exists to prevent. Losing an
     * audit row is the correct trade against losing the request.
     */
    private RouteAuditRecord newAuditRecord(TransactionConfig cfg, Exchange exchange,
                                            String traceId, long startTime) {
        try {
            RouteAuditRecord audit = new RouteAuditRecord();
            audit.setTraceId(traceId);
            audit.setRouteId(cfg.routeId());
            audit.setRoutePath(cfg.path());
            audit.setHttpMethod(exchange.getProperty(AuditExchangeProperties.HTTP_METHOD, String.class));
            audit.setQueryString(exchange.getProperty(AuditExchangeProperties.HTTP_QUERY, String.class));
            audit.setRequestHeaders(auditSanitizer.sanitizeHeaders(exchange.getIn().getHeaders()));
            audit.setStartedAt(Instant.ofEpochMilli(startTime));
            return audit;
        } catch (Throwable t) {
            log.error("Could not start an audit record for route '{}'; this request will not be audited",
                    cfg.routeId(), t);
            return null;
        }
    }

    private String resolveTraceId(Exchange exchange) {
        String incoming = exchange.getIn().getHeader(CORRELATION_HEADER, String.class);
        return incoming != null && !incoming.isBlank() ? incoming : UUID.randomUUID().toString();
    }

    /**
     * Writes the envelope. Cannot throw — {@link ErrorEnvelopeFactory#toJson} falls back to a
     * constant payload if serialization fails.
     * <p>
     * The wire status is 200 for every outcome by default (see {@code engine.http.propagate-status}),
     * so the true status is always published as {@value #ENGINE_STATUS_HEADER} and as a metric.
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
    // Inbound parsing (body + query params)
    // ============================================================

    /**
     * @param raw the body already read by {@code execute}. Passed in rather than re-read: the
     *            exchange body may be a stream, and this method throws on malformed JSON before it
     *            could hand the payload back for the audit row.
     */
    private Map<String, Object> parseIncoming(TransactionConfig cfg, Exchange exchange, String raw) {
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
    // Step dispatch
    // ============================================================

    private void executeSteps(List<OrchestrationStep> steps, Map<String, Object> ctx, String routeId, Exchange exchange) {
        if (steps == null) return;

        for (OrchestrationStep step : steps) {
            if (step == null) {
                throw new OrchestrationException(ErrorCode.CONFIG_ERROR, "null step in route " + routeId);
            }
            if (step.type() == null) {
                // Guarded before the switch: switch-on-null-String throws NPE, which would bypass
                // the UNKNOWN_STEP_TYPE arm entirely and report a generic 500.
                throw new OrchestrationException(ErrorCode.UNKNOWN_STEP_TYPE,
                        "step '" + step.id() + "' has no type");
            }

            String priorStep = MDC.get("stepId");
            MDC.put("stepId", step.id());
            long stepStart = System.currentTimeMillis();
            boolean ok = false;
            try {
                log.info("-- Step START type={}", step.type());
                switch (step.type()) {
                    case "call" -> executeCall(step, ctx);
                    case "businessLogic" -> executeBusinessLogic(step, ctx);
                    case "conditional" -> executeConditional(step, ctx, routeId, exchange);
                    case "parallel" -> executeParallel(step, ctx, routeId, exchange);
                    case "validation" -> executeValidation(step, ctx, exchange);
                    default -> throw new OrchestrationException(ErrorCode.UNKNOWN_STEP_TYPE,
                            "Unknown step type: " + step.type());
                }
                ok = true;
                log.info("-- Step END in {} ms", System.currentTimeMillis() - stepStart);
            } finally {
                metrics.recordStep(routeId, step.id(), step.type(), ok, stepStart);
                // On failure the stepId is deliberately left in the MDC so the boundary can report
                // which step failed. The boundary restores the MDC when it finishes.
                if (ok) {
                    if (priorStep != null) {
                        MDC.put("stepId", priorStep);
                    } else {
                        MDC.remove("stepId");
                    }
                }
            }
        }
    }

    // ============================================================
    // call
    // ============================================================

    private void executeCall(OrchestrationStep step, Map<String, Object> ctx) {
        long callStart = System.currentTimeMillis();
        String resolvedUri = resolveUri(step, ctx);
        String method = step.method() != null ? step.method().toUpperCase() : "GET";

        Object requestInput = Boolean.TRUE.equals(step.useFullContext())
                ? ctx
                : (ctx.containsKey("input") ? ctx.get("input") : ctx);

        Object requestBody = step.requestJoltSpec() != null
                ? joltTransformer.transform(step.requestJoltSpec(), requestInput)
                : requestInput;

        boolean sendBody = !BODYLESS_METHODS.contains(method);
        String requestJson = sendBody ? toJsonSafely(requestBody) : null;

        Map<String, Object> headers = new HashMap<>();
        headers.put(Exchange.HTTP_METHOD, method);
        headers.put("Accept", "application/json");
        if (step.extraHeaders() != null) {
            headers.putAll(step.extraHeaders());
        }
        if (sendBody) {
            headers.put(Exchange.CONTENT_TYPE, "application/json");
        }
        String traceId = MDC.get("traceId");
        if (traceId != null) {
            headers.put(CORRELATION_HEADER, traceId);
        }

        // NEW — attach bearer token if this step declares auth
        if (step.auth() != null) {
            log.info("calling Auth Call {} ", step.auth());
            applyAuthHeader(step.auth(), headers);
        }

        log.info("Call {} {}", method, resolvedUri);
        logPayload("Call request body: {}", requestJson);
        Integer status = null;
        String rawResponseJson = null;
        String outcome = StepCallAuditRecord.OUTCOME_FAILURE;
        try {
            Exchange result = doRequest(step, resolvedUri, requestJson, headers);

            status = result.getMessage().getHeader(Exchange.HTTP_RESPONSE_CODE, Integer.class);

            // NEW — cached token may have been revoked server-side before our TTL expired; refetch once.
            if (status != null && status == 401 && step.auth() != null) {
                log.warn("Call [{}] got 401 with cached token, invalidating and retrying once", step.id());
                authTokenProvider.invalidate(step.auth());
                applyAuthHeader(step.auth(), headers);
                result = doRequest(step, resolvedUri, requestJson, headers);
                status = result.getMessage().getHeader(Exchange.HTTP_RESPONSE_CODE, Integer.class);
            }


            rawResponseJson = result.getMessage().getBody(String.class);

            if (status != null && status >= 400) {
                throw translator.forStatus(status, step.id(), resolvedUri, rawResponseJson);
            }

            log.info("Call completed status={} bytes={}", status,
                    rawResponseJson == null ? 0 : rawResponseJson.length());
            logPayload("Call raw response: {}", rawResponseJson);

            Object rawResponse = parseResponse(rawResponseJson, step, resolvedUri);

            //Added this line bcoz payment callback api giving http status alone with empty response body
            if (rawResponse instanceof Map<?, ?> map) {
                ((Map<String, Object>) rawResponse).put("httpStatus", status);
            }

            if (step.errorCheck() != null) {
                checkForBusinessError(step, rawResponse);
            }
            Object storedValue = step.responseJoltSpec() != null
                    ? joltTransformer.transform(step.responseJoltSpec(), rawResponse)
                    : rawResponse;

            String key = step.responseVariable() != null ? step.responseVariable() : step.id();
            ctx.put(key, storedValue);
            logPayload("Call stored under ctx['{}']: {}", key, storedValue);
            outcome = StepCallAuditRecord.OUTCOME_SUCCESS;
        } finally {
            recordStepCallAudit(step, ctx, resolvedUri, method, requestJson, rawResponseJson,
                    status, outcome, callStart);
        }
    }

    // NEW — extracted so the 401 retry can reuse it without duplicating the try/catch
    private Exchange doRequest(OrchestrationStep step, String uri, String requestJson, Map<String, Object> headers) {
        String callUri = uri
                + (uri.contains("?") ? "&" : "?")
                + "bridgeEndpoint=true&throwExceptionOnFailure=false";
        Exchange result;
        try {
            result = producerTemplate.request(callUri, ex -> {
                if (step.encryptionProfile() != null && StringUtils.isNotBlank(requestJson)) {
                    PayloadCipher cipher = cipherRegistry.resolve(step.encryptionProfile());
                    EncryptedEnvelope encrypted = cipher.encryptByPartnerKey(requestJson);
                    ex.getIn().setBody(toJsonSafely(encrypted));
                } else
                    ex.getIn().setBody(requestJson);
                headers.forEach((k, v) -> ex.getIn().setHeader(k, v));
            });
        } catch (Exception e) {
            throw translator.translateDownstream(e, step.id());
        }
        if (result.getException() != null) {
            throw translator.translateDownstream(result.getException(), step.id());
        }
        if (step.encryptionProfile() != null) {
            Map<String, Object> parsedOutput = new HashMap<>();
            String raw = result.getMessage().getBody(String.class);
            EncryptedEnvelope envelope = null;
            try {
                envelope = objectMapper.readValue(raw, EncryptedEnvelope.class);
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }
            PayloadCipher cipher = cipherRegistry.resolve(step.encryptionProfile());
            String decryptedJson = cipher.decryptByPartnerKey(envelope);
            log.info("Decrypted request body [{}]: {}", step.id(), decryptedJson);
            Object bodyParsed;
            try {
                bodyParsed = objectMapper.readValue(decryptedJson, Object.class);
            } catch (Exception e) {
                throw new OrchestrationException(ErrorCode.MALFORMED_REQUEST_BODY,
                        "Response body is not valid JSON: " + e.getMessage()
                                + " body=" + truncate(raw), e);
            }
            if (!(bodyParsed instanceof Map<?, ?> bodyMap)) {
                throw new OrchestrationException(ErrorCode.MALFORMED_REQUEST_BODY,
                        "Response body must be a JSON object but was "
                                + (bodyParsed == null ? "null" : bodyParsed.getClass().getSimpleName())
                                + " body=" + truncate(raw));
            }
            bodyMap.forEach((k, v) -> parsedOutput.put(String.valueOf(k), v));
            result.getMessage().setBody(toJsonSafely(parsedOutput));
        }

        return result;
    }

    // NEW — small helper so the header-name/prefix defaulting isn't duplicated between the initial
// call and the 401 retry
    private void applyAuthHeader(AuthConfig auth, Map<String, Object> headers) {
        try {
            String headerName = auth.headerName() != null ? auth.headerName() : "Authorization";
            String headerPrefix = auth.headerPrefix() != null ? auth.headerPrefix() : "Bearer ";
            headers.put(headerName, headerPrefix + authTokenProvider.getToken(auth));
        } catch (Exception e) {
            throw new OrchestrationException("AUTH_FAILURE", "DownStream Api Authentication Failed", 401);
        }
    }


    /**
     * Parses a downstream response. When {@code engine.downstream.expect-json} is on (the default)
     * an unparseable body is an error rather than being stored as a raw String — the old behaviour
     * turned an HTML error page into silent nulls and a 200 response with missing fields.
     */
    private Object parseResponse(String json, OrchestrationStep step, String uri) {
        if (json == null || json.isBlank()) {
            return new HashMap<>();
        }
        try {
            return objectMapper.readValue(json, Object.class);
        } catch (Exception e) {
            if (props.getDownstream().isExpectJson()) {
                throw new OrchestrationException(ErrorCode.DOWNSTREAM_INVALID_RESPONSE,
                        "step '" + step.id() + "': response from " + uri
                                + " is not JSON: " + truncate(json), e);
            }
            log.warn("Response from {} is not JSON; storing raw text (engine.downstream.expect-json=false)", uri);
            return json;
        }
    }

    private void recordStepCallAudit(OrchestrationStep step, Map<String, Object> ctx, String uri,
                                     String method, String requestBody, String responseBody,
                                     Integer status, String outcome, long callStart) {
        try {
            StepCallAuditRecord entry = new StepCallAuditRecord();
            entry.setAuditId(String.valueOf(ctx.get("uuid")));
            entry.setTraceId(MDC.get("traceId"));
            entry.setRouteId(MDC.get("routeId"));
            entry.setPartnerCode(String.valueOf(ctx.get("partnerCode")));
            entry.setStepId(step.id());
            entry.setUri(uri);
            entry.setHttpMethod(method);
            entry.setHttpStatus(status);
            entry.setRequestBody(requestBody);
            entry.setResponseBody(responseBody);
            entry.setOutcome(outcome);
            entry.setDurationMs(System.currentTimeMillis() - callStart);
            stepCallAuditWriter.write(entry);
        } catch (Throwable t) {
            log.warn("Failed to build step-call audit entry for step '{}'", step.id(), t);
        }
    }
    // ============================================================
    // businessLogic
    // ============================================================

    private void executeBusinessLogic(OrchestrationStep step, Map<String, Object> ctx) {
        OrchestrationBusinessLogic logic = resolveBean(step.beanRef(), OrchestrationBusinessLogic.class);
        log.info("BusinessLogic invoking bean '{}'", step.beanRef());
        logic.execute(ctx);   // OrchestrationException thrown here propagates naturally
        if (step.auth() != null) {
            log.info("calling Auth Call {} ", step.auth());
            String headerPrefix = step.auth().headerPrefix() != null ? step.auth().headerPrefix() : "Bearer ";
            ctx.put("bearerToken", headerPrefix + authTokenProvider.getToken(step.auth()));
        }
        log.info("BusinessLogic completed");
    }

    private <T> T resolveBean(String beanRef, Class<T> requiredType) {
        Object bean;
        try {
            bean = applicationContext.getBean(beanRef);
        } catch (Exception e) {
            throw new OrchestrationException(ErrorCode.BEAN_NOT_FOUND,
                    "Bean not found: " + beanRef, e);
        }
        if (!requiredType.isInstance(bean)) {
            throw new OrchestrationException(ErrorCode.INVALID_BEAN_TYPE,
                    "Bean '" + beanRef + "' must implement " + requiredType.getSimpleName()
                            + " but is " + bean.getClass().getName());
        }
        return requiredType.cast(bean);
    }

    // ============================================================
    // conditional
    // ============================================================

    private void executeConditional(OrchestrationStep step, Map<String, Object> ctx, String routeId, Exchange exchange) {
        boolean result = evaluateCondition(step.condition(), ctx);
        log.info("Conditional '{}' evaluated to {}", step.condition(), result);
        executeSteps(result ? step.thenSteps() : step.elseSteps(), ctx, routeId, exchange);
    }

    private String resolveResponseJoltSpec(TransactionConfig cfg, Map<String, Object> ctx) {
        if (cfg.responseJoltSpecRules() != null) {
            for (ConditionalJoltSpec rule : cfg.responseJoltSpecRules()) {
                if (evaluateCondition(rule.condition(), ctx)) {
                    log.info("Matched response JOLT rule. condition={}, spec={}", rule.condition(), rule.joltSpec());
                    return rule.joltSpec();
                }
            }
        }

        return cfg.responseJoltSpec();
    }

    // ============================================================
    // parallel
    // ============================================================

    private void executeParallel(OrchestrationStep step, Map<String, Object> ctx, String routeId, Exchange exchange) {
        List<OrchestrationStep> branches = step.branches();
        log.info("Parallel starting {} branch(es)", branches.size());

        List<CompletableFuture<Map<String, Object>>> futures = branches.stream()
                .map(branch -> CompletableFuture.supplyAsync(
                        MdcPropagation.wrap(() -> {
                            Map<String, Object> branchCtx = new HashMap<>(ctx);
                            executeSteps(List.of(branch), branchCtx, routeId, exchange);
                            return branchCtx;
                        }),
                        parallelExecutor))
                .toList();

        try {
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                    .get(props.getParallel().getTimeoutMs(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            cancelAll(futures);
            throw new OrchestrationException(ErrorCode.PARALLEL_TIMEOUT,
                    "Parallel step '" + step.id() + "' exceeded "
                            + props.getParallel().getTimeoutMs() + " ms", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            cancelAll(futures);
            throw new OrchestrationException(ErrorCode.PARALLEL_STEP_FAILED,
                    "Parallel step '" + step.id() + "' interrupted", e);
        } catch (ExecutionException e) {
            // Cancel the siblings: previously the first failure abandoned the loop while the other
            // branches kept running and performing side effects after the response had been sent.
            cancelAll(futures);
            throw translator.translate(e.getCause() != null ? e.getCause() : e);
        }

        mergeBranchResults(step, ctx, futures);
        log.info("Parallel all branches complete");
    }

    private void cancelAll(List<CompletableFuture<Map<String, Object>>> futures) {
        futures.forEach(f -> f.cancel(true));
    }

    /**
     * Merges branch outputs back into the shared context.
     * <p>
     * Only keys a branch added or changed are merged. Two branches writing the same key used to
     * silently overwrite in submission order; that is now an error by default.
     */
    private void mergeBranchResults(OrchestrationStep step, Map<String, Object> ctx,
                                    List<CompletableFuture<Map<String, Object>>> futures) {
        Map<String, Object> merged = new LinkedHashMap<>();
        Set<String> claimed = new HashSet<>();

        for (CompletableFuture<Map<String, Object>> future : futures) {
            Map<String, Object> branchCtx = future.join();
            for (Map.Entry<String, Object> entry : branchCtx.entrySet()) {
                String key = entry.getKey();
                boolean changed = !ctx.containsKey(key) || ctx.get(key) != entry.getValue();
                if (!changed) {
                    continue;
                }
                if (!claimed.add(key)) {
                    if (props.getParallel().isFailOnKeyCollision()) {
                        throw new OrchestrationException(ErrorCode.PARALLEL_STEP_FAILED,
                                "Parallel step '" + step.id() + "': two branches both wrote ctx['"
                                        + key + "']; set engine.parallel.fail-on-key-collision=false "
                                        + "to allow last-writer-wins");
                    }
                    log.warn("Parallel step '{}': ctx['{}'] written by more than one branch", step.id(), key);
                }
                merged.put(key, entry.getValue());
            }
        }
        ctx.putAll(merged);
    }

    // ============================================================
    // validation
    // ============================================================

    private void executeValidation(OrchestrationStep step, Map<String, Object> ctx, Exchange exchange) {
        List<ValidationError> errors = new ArrayList<ValidationError>();


        if (step.requiredFields() != null) {
            for (String path : step.requiredFields()) {
                Object value = resolvePath(ctx, path);
                if (value == null || value.toString().isBlank()) {
                    String displayField = path.contains(".") ? path.substring(path.lastIndexOf('.') + 1) : path;
                    errors.add(new ValidationError("REQUIRED_FIELD", displayField + " is required"));

                }
            }
        }
        if (step.fieldValidations() != null) {
            for (ValidationRule rule : step.fieldValidations()) {
                Object bean = applicationContext.getBean(rule.beanRef());
                FieldValidator validator = (FieldValidator) bean;

                for (String path : rule.fields()) {
                    Object value = resolvePath(ctx, path);   // you already have this helper
                    if (!(bean instanceof ConditionalRequiredValidator) && value == null)
                        continue;              // requiredFields already checks presence

                    boolean valid = validator.isValid(value, rule.params(), ctx);
                    if (!valid) {
                        String displayField = path.contains(".") ? path.substring(path.lastIndexOf('.') + 1) : path;
                        String message = rule.errorMessage() != null
                                ? rule.errorMessage().replace("{field}", displayField)
                                : displayField + " is invalid";
                        errors.add(new ValidationError(rule.errorCode(), message));
                    }
                }
            }
        }

        if (step.beanRef() != null) {
            OrchestrationValidator validator = resolveBean(step.beanRef(), OrchestrationValidator.class);
            List<String> beanErrors;
            try {
                beanErrors = validator.validate(ctx);
            } catch (OrchestrationException e) {
                throw e;
            } catch (Exception e) {
                throw new OrchestrationException(ErrorCode.VALIDATOR_FAILED,
                        "Validator bean '" + step.beanRef() + "' threw: " + e.getMessage(), e);
            }
            if (beanErrors != null) {
                for (String msg : beanErrors) {
                    errors.add(new ValidationError("BUSINESS_VALIDATION_FAILED", msg));
                }

            }
        }
        if (!errors.isEmpty()) {
            List<String> errorCodes = errors.stream()
                    .map(ValidationError::code)
                    .toList();
            List<String> errorMessages = errors.stream()
                    .map(ValidationError::message)
                    .toList();

            throw new ValidationException(errorCodes, errorMessages);
        }
        log.info("Validation passed");
    }


    private void checkForBusinessError(OrchestrationStep step, Object rawResponse) {
        if (!(rawResponse instanceof Map<?, ?> responseMap)) {
            return; // nothing to evaluate a condition against
        }

        ErrorCheckConfig check = step.errorCheck();
        boolean isError;
        try {
            ExpressionParser parser = new SpelExpressionParser();
            StandardEvaluationContext evalCtx = new StandardEvaluationContext(responseMap);
            isError = Boolean.TRUE.equals(
                    parser.parseExpression(check.condition()).getValue(evalCtx, Boolean.class));
        } catch (Exception e) {
            log.warn("Call [{}] error-check condition failed to evaluate, treating as no error: {}",
                    step.id(), e.getMessage());
            return;
        }

        if (!isError) {
            return;
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> responseAsMap = (Map<String, Object>) responseMap;

        String code = check.codeField() != null
                ? String.valueOf(resolvePath(responseAsMap, check.codeField()))
                : check.defaultCode();
        if (code == null || code.equals("null")) {
            code = check.defaultCode() != null ? check.defaultCode() : "DOWNSTREAM_BUSINESS_ERROR";
        }

        String message = check.messageField() != null
                ? String.valueOf(resolvePath(responseAsMap, check.messageField()))
                : "Downstream service reported an error";
        if (message.equals("null")) {
            message = "Downstream service reported an error";
        }

        int httpStatus = check.httpStatus() != null ? check.httpStatus() : 400;

        log.warn("Call [{}] business error detected: code={}, message={}", step.id(), code, message);
        throw new OrchestrationException(code, message, httpStatus);
    }
    // ============================================================
    // Helpers
    // ============================================================

    /**
     * Substitutes {@code {{path}}} placeholders.
     * <p>
     * Values are URL-encoded — they originate in partner request bodies, and an unencoded
     * {@code &} or {@code ?} would inject extra query parameters into an internal service call.
     * Encoding is query-value form, which is where every placeholder in the shipped configs sits.
     * An unresolvable placeholder is now an error rather than a silent empty substitution.
     */
    private String resolveUri(OrchestrationStep step, Map<String, Object> ctx) {
        String uriTemplate = step.uri();
        if (uriTemplate == null) {
            throw new OrchestrationException(ErrorCode.CONFIG_ERROR,
                    "call step '" + step.id() + "' has no uri");
        }
        Matcher m = PLACEHOLDER_PATTERN.matcher(uriTemplate);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String path = m.group(1).trim();
            Object value = resolvePath(ctx, path);
            if (value == null || value.toString().isBlank()) {
                throw new OrchestrationException(ErrorCode.URI_PLACEHOLDER_UNRESOLVED,
                        "step '" + step.id() + "': placeholder '{{" + path + "}}' did not resolve");
            }
            String encoded = URLEncoder.encode(value.toString(), StandardCharsets.UTF_8);
            m.appendReplacement(sb, Matcher.quoteReplacement(encoded));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private Object resolvePath(Map<String, Object> ctx, String path) {
        Object current = ctx;
        for (String part : path.split("\\.")) {
            if (!(current instanceof Map)) return null;
            current = ((Map<?, ?>) current).get(part);
        }
        return current;
    }

    private String toJsonSafely(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            throw new OrchestrationException(ErrorCode.SERIALIZATION_FAILED,
                    "Failed to serialize request body: " + e.getMessage(), e);
        }
    }

    /**
     * Payloads contain customer PII (names, registration numbers, quotes), so they log at DEBUG
     * unless {@code engine.logging.payloads} is explicitly enabled.
     */
    private void logPayload(String message, Object... args) {
        if (props.getLogging().isPayloads()) {
            log.info(message, args);
        } else if (log.isDebugEnabled()) {
            log.debug(message, args);
        }
    }

    private String truncate(String value) {
        int max = props.getDownstream().getMaxLogBodyChars();
        if (value == null || value.length() <= max) return value;
        return value.substring(0, max) + "...<truncated>";
    }

    private static int sizeOf(Object value) {
        return value instanceof Map<?, ?> m ? m.size() : 0;
    }

    private String resolveUUID(Map ctx) {
        String uuid = null;
        Object inputObj = ctx.get("input");
        if (inputObj instanceof Map<?, ?> inputMap) {
            Object uuidObj = inputMap.get("uuid");
            if (uuidObj != null) {
                uuid = uuidObj.toString();
            }
        }
        // Generated here rather than by the database so the same code works on Oracle
        // (SEQUENCE), PostgreSQL (IDENTITY) and SQL Server without dialect-specific key retrieval.
        if (StringUtils.isBlank(uuid))
            uuid = UUID.randomUUID().toString();

        return uuid;
    }

    private boolean evaluateCondition(String condition, Map<String, Object> ctx) {
        EvaluationContext evalCtx = SimpleEvaluationContext
                .forPropertyAccessors(new MapAccessor())
                .withRootObject(ctx)
                .build();

        try {
            return Boolean.TRUE.equals(new SpelExpressionParser()
                    .parseExpression(condition).getValue(evalCtx, Boolean.class));

        } catch (Exception e) {
            throw new OrchestrationException(ErrorCode.INVALID_CONDITION,
                    "Failed to evaluate condition '" + condition + "': " + e.getMessage(), e);
        }
    }

    private void openApiValidation(OpenApiValidationConfig cfg, Map<String, Object> ctx, Exchange exchange) {
        OpenApiInteractionValidator validator =
                openApiValidatorRegistry.get(cfg.specLocation());
        String requestBody = toJsonSafely(ctx.get("rawInput"));
        log.info("OPENAPI REQUEST BODY={}", requestBody);
        String authHeader = exchange.getIn().getHeader("Authorization", String.class);
        log.info("AUTH HEADER={}", authHeader);
        SimpleRequest.Builder builder =
                new SimpleRequest.Builder(
                        cfg.httpMethod(),
                        cfg.operationPath())
                        .withBody(requestBody)
                        .withContentType("application/json");
        if (authHeader != null && !authHeader.isBlank()) {
            builder.withHeader("Authorization", authHeader);
        }
        Request request = builder.build();
        ValidationReport report = validator.validateRequest(request);
        if (report.hasErrors()) {
            for (ValidationReport.Message message : report.getMessages()) {
                throw new OrchestrationException("OPENAPI_VALIDATION_FAILED", message.getMessage(), 400);

            }
        }
    }
}
