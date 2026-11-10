package com.rsa.routeforge.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsa.routeforge.audit.AuditPayloadSanitizer;
import com.rsa.routeforge.audit.RouteAuditRecord;
import com.rsa.routeforge.audit.RouteAuditWriter;
import com.rsa.routeforge.audit.StepCallAuditWriter;
import com.rsa.routeforge.auth.AuthTokenProvider;
import com.rsa.routeforge.crypto.CipherRegistry;
import com.rsa.routeforge.error.ErrorEnvelopeFactory;
import com.rsa.routeforge.error.ExceptionTranslator;
import com.rsa.routeforge.error.OrchestrationException;
import com.rsa.routeforge.inbound.InboundCodec;
import com.rsa.routeforge.inbound.InboundCodecRegistry;
import com.rsa.routeforge.inbound.JsonCodec;
import com.rsa.routeforge.openapi.OpenApiValidatorRegistry;
import com.rsa.routeforge.partner.PartnerResolverService;
import org.apache.camel.Exchange;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.support.DefaultExchange;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The engine hands parsing and writing to whichever codec claims the route, and keeps JSON for
 * every other route. This is the seam the SOAP channel plugs into.
 */
class InboundCodecRoutingTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private DefaultCamelContext camelContext;
    private RecordingCodec custom;
    private OrchestrationEngine engine;

    @BeforeEach
    void setUp() {
        EngineProperties props = new EngineProperties();
        ErrorEnvelopeFactory envelopes = new ErrorEnvelopeFactory(mapper);
        CipherRegistry ciphers = new CipherRegistry(List.of());
        AuditPayloadSanitizer sanitizer = mock(AuditPayloadSanitizer.class);
        when(sanitizer.sanitizeHeaders(any())).thenReturn("{}");
        camelContext = new DefaultCamelContext();
        custom = new RecordingCodec();

        engine = new OrchestrationEngine(
                new ExceptionTranslator(500), mock(ProducerTemplate.class), mock(ApplicationContext.class),
                mock(JoltTransformer.class), mapper, props, envelopes, mock(EngineMetrics.class),
                mock(StepCallAuditWriter.class), mock(RouteAuditWriter.class), sanitizer, ciphers,
                mock(AuthTokenProvider.class), mock(ExecutorService.class), mock(PartnerResolverService.class),
                mock(OpenApiValidatorRegistry.class),
                new InboundCodecRegistry(List.of(custom, new JsonCodec(mapper, props, envelopes, ciphers))));
    }

    @AfterEach
    void tearDown() {
        camelContext.stop();
    }

    @Test
    void claimedRouteIsParsedAndWrittenByItsCodec() {
        Exchange ex = new DefaultExchange(camelContext);
        ex.getIn().setBody("<not-json/>");

        engine.execute(route("custom-op"), ex);

        assertEquals("<not-json/>", custom.parsedRaw);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) custom.successBody;
        assertNotNull(body, "success written by the custom codec");
        assertEquals(Map.of("field", "value"), body.get("input"), "codec output becomes ctx.input");
        assertNull(custom.error);
        assertEquals("written-by-custom", ex.getIn().getBody(String.class));
    }

    @Test
    void failureOnClaimedRouteIsWrittenByItsCodec() {
        custom.failParse = true;
        Exchange ex = new DefaultExchange(camelContext);
        ex.getIn().setBody("<bad/>");

        engine.execute(route("custom-op"), ex);

        assertNotNull(custom.error);
        assertEquals("CUSTOM_BAD", custom.error.getCode());
        assertNull(custom.successBody);
        assertEquals("error-by-custom", ex.getIn().getBody(String.class));
    }

    @Test
    void unclaimedRouteStillUsesJson() throws Exception {
        Exchange ex = new DefaultExchange(camelContext);
        ex.getIn().setBody("{\"a\":1}");

        engine.execute(route("bimasugam-quote"), ex);

        assertNull(custom.parsedRaw);
        assertEquals("200", mapper.readTree(ex.getIn().getBody(String.class)).get("status_code").asText());
    }

    private static TransactionConfig route(String routeId) {
        return new TransactionConfig(routeId, "/" + routeId, null, null, null, null, null, "POST", null, null);
    }

    /** Claims routes whose id starts with {@code custom-} and records what the engine handed it. */
    private static final class RecordingCodec implements InboundCodec {
        String parsedRaw;
        Object successBody;
        OrchestrationException error;
        boolean failParse;

        @Override
        public boolean supports(TransactionConfig cfg) {
            return cfg.routeId().startsWith("custom-");
        }

        @Override
        public Map<String, Object> parseRequest(TransactionConfig cfg, Exchange exchange, String rawBody) {
            parsedRaw = rawBody;
            if (failParse) {
                throw new OrchestrationException("CUSTOM_BAD", "bad request", 400);
            }
            Map<String, Object> input = new HashMap<>();
            input.put("field", "value");
            return input;
        }

        @Override
        public void writeSuccess(Exchange exchange, TransactionConfig cfg, Object body, String uuid,
                                 String traceId, RouteAuditRecord audit) {
            successBody = body;
            exchange.getIn().setBody("written-by-custom");
        }

        @Override
        public void writeError(Exchange exchange, TransactionConfig cfg, OrchestrationException error,
                               String traceId, String failedStep, String uuid, RouteAuditRecord audit) {
            this.error = error;
            exchange.getIn().setBody("error-by-custom");
        }
    }
}
