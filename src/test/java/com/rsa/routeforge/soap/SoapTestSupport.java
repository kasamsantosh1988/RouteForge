package com.rsa.routeforge.soap;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsa.routeforge.audit.AuditPayloadSanitizer;
import com.rsa.routeforge.audit.RouteAuditRecord;
import com.rsa.routeforge.audit.RouteAuditWriter;
import com.rsa.routeforge.audit.StepCallAuditWriter;
import com.rsa.routeforge.auth.AuthTokenProvider;
import com.rsa.routeforge.config.EngineMetrics;
import com.rsa.routeforge.config.EngineProperties;
import com.rsa.routeforge.config.ErrorCheckConfig;
import com.rsa.routeforge.config.JoltTransformer;
import com.rsa.routeforge.config.OrchestrationBusinessLogic;
import com.rsa.routeforge.config.OrchestrationEngine;
import com.rsa.routeforge.config.OrchestrationStep;
import com.rsa.routeforge.config.TransactionConfig;
import com.rsa.routeforge.crypto.CipherRegistry;
import com.rsa.routeforge.error.ErrorEnvelopeFactory;
import com.rsa.routeforge.error.ExceptionTranslator;
import com.rsa.routeforge.inbound.InboundCodecRegistry;
import com.rsa.routeforge.inbound.JsonCodec;
import com.rsa.routeforge.openapi.OpenApiValidatorRegistry;
import com.rsa.routeforge.partner.PartnerResolverService;
import com.rsa.routeforge.route.RouteForgeProperties;
import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.support.DefaultExchange;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationContext;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

import static com.rsa.routeforge.soap.SoapSamples.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * An engine wired with the SOAP and JSON codecs for SOAP tests, with a stand-in credentials API
 * behind {@code call} steps: it answers {@code {"valid": true|false, ...}} and accepts only
 * {@code test-password}.
 */
final class SoapTestSupport {

    static final String CREDENTIALS_URI = "http://credentials.test/verify";

    final ObjectMapper mapper = new ObjectMapper();
    final EngineProperties props = new EngineProperties();
    final RouteForgeProperties routeForgeProperties = new RouteForgeProperties();
    final RouteAuditWriter auditWriter = mock(RouteAuditWriter.class);
    final EngineMetrics metrics = mock(EngineMetrics.class);
    final ApplicationContext applicationContext = mock(ApplicationContext.class);
    final ProducerTemplate producer = mock(ProducerTemplate.class);
    /** Context seen by the echo step; empty when the route failed before reaching it. */
    final Map<String, Object> lastCtx = new HashMap<>();
    /** Request bodies sent to the credentials API. */
    final List<Map<?, ?>> credentialRequests = new ArrayList<>();
    final OrchestrationEngine engine;

    SoapTestSupport(CamelContext camel) {
        AuditPayloadSanitizer sanitizer = mock(AuditPayloadSanitizer.class);
        when(sanitizer.sanitizeHeaders(any())).thenReturn("{}");

        // A businessLogic step that echoes the decoded input back as the SOAP return value.
        OrchestrationBusinessLogic echo = ctx -> {
            lastCtx.clear();
            lastCtx.putAll(ctx);
            Map<?, ?> input = (Map<?, ?>) ctx.get("input");
            ctx.put(SoapCodec.RETURN_KEY, "OK~~" + input.get("data"));
        };
        when(applicationContext.getBean("echoReturn")).thenReturn(echo);

        when(producer.request(anyString(), any(Processor.class))).thenAnswer(inv -> {
            Exchange request = new DefaultExchange(camel);
            ((Processor) inv.getArgument(1)).process(request);
            Map<?, ?> body = mapper.readValue(request.getIn().getBody(String.class), Map.class);
            credentialRequests.add(body);
            boolean valid = "test-password".equals(body.get("password"));
            Exchange response = new DefaultExchange(camel);
            response.getMessage().setHeader(Exchange.HTTP_RESPONSE_CODE, 200);
            response.getMessage().setBody(valid
                    ? "{\"valid\":true}"
                    : "{\"valid\":false,\"code\":\"INVALID_CREDENTIALS\",\"message\":\"Invalid user name or password\"}");
            return response;
        });

        ErrorEnvelopeFactory envelopes = new ErrorEnvelopeFactory(mapper);
        CipherRegistry ciphers = new CipherRegistry(List.of());
        engine = new OrchestrationEngine(
                new ExceptionTranslator(500), producer, applicationContext,
                mock(JoltTransformer.class), mapper, props, envelopes, metrics,
                mock(StepCallAuditWriter.class), auditWriter, sanitizer, ciphers,
                mock(AuthTokenProvider.class), mock(ExecutorService.class), mock(PartnerResolverService.class),
                mock(OpenApiValidatorRegistry.class),
                new InboundCodecRegistry(List.of(new SoapCodec(routeForgeProperties, metrics),
                        new JsonCodec(mapper, props, envelopes, ciphers))));
    }

    RouteAuditRecord lastAudit() {
        ArgumentCaptor<RouteAuditRecord> captor = ArgumentCaptor.forClass(RouteAuditRecord.class);
        verify(auditWriter, atLeastOnce()).write(captor.capture());
        return captor.getValue();
    }

    /** The login check as a route step: a call to the credentials API, failing on {@code valid == false}. */
    static OrchestrationStep verifyLoginStep() {
        return new OrchestrationStep("verifyLogin", "call", CREDENTIALS_URI, "POST", null, null, "login",
                null, null, null, null, null, null, null,
                new ErrorCheckConfig("['valid'] == false", "code", "message", "INVALID_CREDENTIALS", 401),
                null, null, null, null);
    }

    static OrchestrationStep echoStep() {
        return OrchestrationStep.businessLogic("echo", "echoReturn");
    }

    /** MIBL proposal checking its login with a route step. */
    static TransactionConfig miblRoute() {
        return miblRoute(List.of(verifyLoginStep(), echoStep()));
    }

    static TransactionConfig miblRoute(List<OrchestrationStep> steps) {
        SoapRouteConfig soap = new SoapRouteConfig("web:MIBLProposalInfo", MIBL_PREFIXES, null,
                Map.of("data", "miblProposalData", "userName", "soapHeader/userName", "password", "soapHeader/password"),
                MIBL_RESPONSE, null, null);
        return new TransactionConfig("mibl-proposal", "/ws/mibl/MiblWebSystem", "MIBL", null,
                steps, null, null, "POST", null, null, TransactionConfig.CHANNEL_SOAP, soap);
    }

    static TransactionConfig marutiRoute() {
        SoapRouteConfig soap = new SoapRouteConfig("prop:MarutiProposalInfoNew", MARUTI_PREFIXES, null,
                Map.of("data", "prop:MarutiProposalStatusInfoNew", "userName", "prop:shIn/auth:userName",
                        "password", "prop:shIn/auth:password"),
                MARUTI_RESPONSE, null, null);
        return new TransactionConfig("maruti-proposal-new", "/ws/maruti/MarutiWebserviceSystem", "MARUTI", null,
                List.of(verifyLoginStep(), echoStep()), null, null, "POST", null, null,
                TransactionConfig.CHANNEL_SOAP, soap);
    }
}
