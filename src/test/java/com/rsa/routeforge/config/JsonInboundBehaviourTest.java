package com.rsa.routeforge.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsa.routeforge.audit.AuditPayloadSanitizer;
import com.rsa.routeforge.audit.RouteAuditRecord;
import com.rsa.routeforge.audit.RouteAuditWriter;
import com.rsa.routeforge.audit.StepCallAuditWriter;
import com.rsa.routeforge.auth.AuthTokenProvider;
import com.rsa.routeforge.crypto.CipherRegistry;
import com.rsa.routeforge.crypto.EncryptedEnvelope;
import com.rsa.routeforge.crypto.PayloadCipher;
import com.rsa.routeforge.error.ErrorEnvelopeFactory;
import com.rsa.routeforge.error.ExceptionTranslator;
import com.rsa.routeforge.openapi.OpenApiValidatorRegistry;
import com.rsa.routeforge.partner.PartnerResolverService;
import org.apache.camel.Exchange;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.support.DefaultExchange;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationContext;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ExecutorService;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Pins the engine's current JSON inbound behaviour: how a request body and headers become
 * {@code ctx.input}, and the exact response envelope, headers and audit fields that go back.
 * <p>
 * These tests exist so the request parsing and response writing can be moved out of
 * {@link OrchestrationEngine} without any partner noticing. They describe what the engine does
 * today, including quirks (the double space in {@code "INVALID  REQUEST"}, a {@code null} uuid on
 * early failures); changing any of them is a behaviour change and must be deliberate.
 */
class JsonInboundBehaviourTest {

    private static final String PROFILE = "test-profile";

    private final ObjectMapper mapper = new ObjectMapper();
    private EngineProperties props;
    private RouteAuditWriter auditWriter;
    private PartnerResolverService partnerResolver;
    private DefaultCamelContext camelContext;
    private OrchestrationEngine engine;

    @BeforeEach
    void setUp() {
        props = new EngineProperties();
        auditWriter = mock(RouteAuditWriter.class);
        partnerResolver = mock(PartnerResolverService.class);
        AuditPayloadSanitizer sanitizer = mock(AuditPayloadSanitizer.class);
        when(sanitizer.sanitizeHeaders(any())).thenReturn("{}");
        camelContext = new DefaultCamelContext();

        engine = new OrchestrationEngine(
                new ExceptionTranslator(500),
                mock(ProducerTemplate.class),
                mock(ApplicationContext.class),
                mock(JoltTransformer.class),
                mapper,
                props,
                new ErrorEnvelopeFactory(mapper),
                mock(EngineMetrics.class),
                mock(StepCallAuditWriter.class),
                auditWriter,
                sanitizer,
                new CipherRegistry(List.of(new Base64TestCipher())),
                mock(AuthTokenProvider.class),
                mock(ExecutorService.class),
                partnerResolver,
                mock(OpenApiValidatorRegistry.class));
    }

    @AfterEach
    void tearDown() {
        camelContext.stop();
    }

    // ------------------------------------------------------------------ plain JSON

    @Test
    void plainJsonSuccessBuildsInputFromBodyAndHeadersAndWritesEnvelope() throws Exception {
        when(partnerResolver.getPartnerIdByPartnerCode("MIBL")).thenReturn(42L);
        Exchange ex = exchange("{\"a\":1,\"uuid\":\"u-1\",\"channel\":\"from-body\"}");
        ex.getIn().setHeader("X-Correlation-Id", "trace-1");
        ex.getIn().setHeader("Authorization", "Bearer secret");
        ex.getIn().setHeader("Content-Type", "application/json");
        ex.getIn().setHeader("channel", "from-header");
        ex.getIn().setHeader("source", "web");

        engine.execute(route("mibl-proposal", "MIBL", null), ex);

        assertEquals(200, ex.getIn().getHeader(Exchange.HTTP_RESPONSE_CODE));
        assertEquals(200, ex.getIn().getHeader("X-Engine-Status"));
        assertEquals("trace-1", ex.getIn().getHeader("X-Correlation-Id"));
        assertEquals("application/json", ex.getIn().getHeader(Exchange.CONTENT_TYPE));
        assertEquals("application/json", ex.getIn().getHeader("Accept"));

        String body = ex.getIn().getBody(String.class);
        JsonNode root = mapper.readTree(body);
        assertEquals(List.of("uuid", "status_code", "error", "data"), fieldNames(root));
        assertEquals("u-1", root.get("uuid").asText());
        assertEquals("200", root.get("status_code").asText());
        assertEquals("", root.get("error").get("code").asText());
        assertEquals("", root.get("error").get("message").asText());

        // No responseJoltSpec: the whole context is returned as data.
        JsonNode data = root.get("data");
        JsonNode input = data.get("input");
        assertEquals(1, input.get("a").asInt());
        assertEquals("from-body", input.get("channel").asText(), "body wins over a header of the same name");
        assertEquals("web", input.get("source").asText(), "other headers are merged into input");
        assertFalse(input.has("Authorization"));
        assertFalse(input.has("X-Correlation-Id"));
        assertFalse(input.has("Content-Type"));
        assertEquals(input, data.get("rawInput"), "without requestJoltSpec rawInput and input are the same map");
        assertEquals("MIBL", data.get("partnerCode").asText());
        assertEquals(42, data.get("partnerId").asInt());
        assertEquals("u-1", data.get("uuid").asText());

        RouteAuditRecord audit = capturedAudit();
        assertEquals(RouteAuditRecord.OUTCOME_SUCCESS, audit.getOutcome());
        assertEquals("{\"a\":1,\"uuid\":\"u-1\",\"channel\":\"from-body\"}", audit.getRequestBody());
        assertEquals(body, audit.getResponseBody());
        assertEquals("u-1", audit.getAuditId());
        assertEquals("MIBL", audit.getPartnerCode());
        assertEquals(Integer.valueOf(200), audit.getLogicalStatus());
        assertEquals(Integer.valueOf(200), audit.getWireStatus());
    }

    @Test
    void generatesUuidWhenRequestHasNone() throws Exception {
        Exchange ex = exchange("{\"a\":1}");

        engine.execute(route("r1", null, null), ex);

        JsonNode root = mapper.readTree(ex.getIn().getBody(String.class));
        String uuid = root.get("uuid").asText();
        assertEquals(36, uuid.length());
        assertEquals(uuid, root.get("data").get("uuid").asText());
        assertTrue(root.get("data").has("partnerCode"));
        assertTrue(root.get("data").get("partnerCode").isNull());
        assertFalse(root.get("data").has("partnerId"));
    }

    @Test
    void emptyBodyTakesInputFromHeadersOnly() throws Exception {
        Exchange ex = exchange("");
        ex.getIn().setHeader("policyNo", "P-1");

        engine.execute(route("r1", null, null), ex);

        JsonNode root = mapper.readTree(ex.getIn().getBody(String.class));
        assertEquals("200", root.get("status_code").asText());
        assertEquals("P-1", root.get("data").get("input").get("policyNo").asText());
    }

    @Test
    void malformedJsonIsInvalidRequestWithHttp200OnTheWire() throws Exception {
        Exchange ex = exchange("{bad");
        ex.getIn().setHeader("X-Correlation-Id", "trace-2");

        engine.execute(route("r1", null, null), ex);

        assertEquals(200, ex.getIn().getHeader(Exchange.HTTP_RESPONSE_CODE));
        assertEquals(400, ex.getIn().getHeader("X-Engine-Status"));
        JsonNode root = mapper.readTree(ex.getIn().getBody(String.class));
        assertEquals(List.of("uuid", "status_code", "error", "data"), fieldNames(root));
        assertTrue(root.get("uuid").isNull(), "uuid is not resolved before parsing fails");
        assertEquals("400", root.get("status_code").asText());
        JsonNode error = root.get("error");
        assertEquals(List.of("code", "message", "traceId", "step", "details"), fieldNames(error));
        assertEquals("INVALID_REQUEST", error.get("code").asText());
        assertEquals("INVALID  REQUEST", error.get("message").asText());
        assertEquals("trace-2", error.get("traceId").asText());
        assertTrue(error.get("step").isNull());
        assertEquals(0, error.get("details").size());
        assertTrue(root.get("data").isNull());

        RouteAuditRecord audit = capturedAudit();
        assertEquals(RouteAuditRecord.OUTCOME_FAILURE, audit.getOutcome());
        assertEquals("INVALID_REQUEST", audit.getErrorCode());
        assertEquals("{bad", audit.getRequestBody());
        assertEquals(Integer.valueOf(400), audit.getLogicalStatus());
        assertEquals(Integer.valueOf(200), audit.getWireStatus());
    }

    @Test
    void jsonArrayBodyIsInvalidRequest() throws Exception {
        Exchange ex = exchange("[1,2]");

        engine.execute(route("r1", null, null), ex);

        JsonNode error = mapper.readTree(ex.getIn().getBody(String.class)).get("error");
        assertEquals("INVALID_REQUEST", error.get("code").asText());
        assertEquals("INVALID  REQUEST", error.get("message").asText());
    }

    @Test
    void propagatedStatusAppliesExceptForAlways200Routes() {
        props.getHttp().setPropagateStatus(true);

        Exchange other = exchange("{bad");
        engine.execute(route("oem-proposal", null, null), other);
        assertEquals(400, other.getIn().getHeader(Exchange.HTTP_RESPONSE_CODE));

        Exchange bima = exchange("{bad");
        engine.execute(route("bimasugam-quote", null, null), bima);
        assertEquals(200, bima.getIn().getHeader(Exchange.HTTP_RESPONSE_CODE));
        assertEquals(400, bima.getIn().getHeader("X-Engine-Status"));
    }

    // ------------------------------------------------------------------ encrypted JSON

    @Test
    void encryptedRequestIsDecryptedAndResponseEncrypted() throws Exception {
        String plain = "{\"a\":7,\"uuid\":\"u-9\"}";
        Exchange ex = exchange("{\"encrypted_data\":\"" + b64(plain) + "\",\"key_id\":\"k1\"}");

        engine.execute(route("r1", null, PROFILE), ex);

        assertEquals(200, ex.getIn().getHeader(Exchange.HTTP_RESPONSE_CODE));
        String wire = ex.getIn().getBody(String.class);
        EncryptedEnvelope sealed = mapper.readValue(wire, EncryptedEnvelope.class);
        assertEquals("k1", sealed.keyId());
        assertEquals(List.of("encrypted_data", "key_id"), fieldNames(mapper.readTree(wire)));

        JsonNode root = mapper.readTree(unb64(sealed.encryptedData()));
        assertEquals("u-9", root.get("uuid").asText());
        assertEquals("200", root.get("status_code").asText());
        assertEquals(7, root.get("data").get("input").get("a").asInt());

        RouteAuditRecord audit = capturedAudit();
        assertEquals(root, mapper.readTree(audit.getResponseBody()), "audit keeps the plaintext envelope");
    }

    @Test
    void encryptedRequestWithoutKeyIdIsInvalidEncryptedRequest() throws Exception {
        Exchange ex = exchange("{\"encrypted_data\":\"" + b64("{}") + "\"}");

        engine.execute(route("r1", null, PROFILE), ex);

        // The error envelope itself is encrypted on the way out.
        EncryptedEnvelope sealed = mapper.readValue(ex.getIn().getBody(String.class), EncryptedEnvelope.class);
        JsonNode error = mapper.readTree(unb64(sealed.encryptedData())).get("error");
        assertEquals("INVALID_REQUEST", error.get("code").asText());
        assertEquals("INVALID ENCRYPTED REQUEST", error.get("message").asText());
    }

    @Test
    void encryptedPayloadThatIsNotJsonIsInvalidEncryptedRequest() throws Exception {
        Exchange ex = exchange("{\"encrypted_data\":\"" + b64("not json") + "\",\"key_id\":\"k1\"}");

        engine.execute(route("r1", null, PROFILE), ex);

        EncryptedEnvelope sealed = mapper.readValue(ex.getIn().getBody(String.class), EncryptedEnvelope.class);
        JsonNode error = mapper.readTree(unb64(sealed.encryptedData())).get("error");
        assertEquals("INVALID_REQUEST", error.get("code").asText());
        assertEquals("INVALID ENCRYPTED REQUEST", error.get("message").asText());
    }

    // ------------------------------------------------------------------ helpers

    private Exchange exchange(String body) {
        Exchange ex = new DefaultExchange(camelContext);
        ex.getIn().setBody(body);
        return ex;
    }

    private static TransactionConfig route(String routeId, String partnerCode, String encryptionProfile) {
        return new TransactionConfig(routeId, "/test/" + routeId, partnerCode, null, null, null,
                encryptionProfile, "POST", null, null);
    }

    private RouteAuditRecord capturedAudit() {
        ArgumentCaptor<RouteAuditRecord> captor = ArgumentCaptor.forClass(RouteAuditRecord.class);
        verify(auditWriter).write(captor.capture());
        return captor.getValue();
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        Iterator<String> it = node.fieldNames();
        it.forEachRemaining(names::add);
        return names;
    }

    private static String b64(String s) {
        return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    private static String unb64(String s) {
        return new String(Base64.getDecoder().decode(s), StandardCharsets.UTF_8);
    }

    /** Reversible stand-in for a real cipher: Base64 in place of encryption. */
    private static final class Base64TestCipher implements PayloadCipher {
        @Override public String name() { return PROFILE; }
        @Override public EncryptedEnvelope encrypt(String plaintext) { return new EncryptedEnvelope(b64(plaintext), "k1"); }
        @Override public String decrypt(EncryptedEnvelope envelope) { return unb64(envelope.encryptedData()); }
        @Override public EncryptedEnvelope encryptByPartnerKey(String plaintext) { throw new UnsupportedOperationException(); }
        @Override public String decryptByPartnerKey(EncryptedEnvelope envelope) { throw new UnsupportedOperationException(); }
        @Override public String decrypt(String data, String keyId) { throw new UnsupportedOperationException(); }
    }
}
