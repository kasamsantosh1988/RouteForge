package com.rsa.routeforge.soap;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsa.routeforge.audit.AuditPayloadSanitizer;
import com.rsa.routeforge.audit.RouteAuditRecord;
import com.rsa.routeforge.audit.RouteAuditWriter;
import com.rsa.routeforge.audit.StepCallAuditWriter;
import com.rsa.routeforge.auth.AuthTokenProvider;
import com.rsa.routeforge.config.EngineMetrics;
import com.rsa.routeforge.config.EngineProperties;
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
import org.apache.camel.ProducerTemplate;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationContext;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

import static com.rsa.routeforge.soap.SoapSamples.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** An engine wired with the SOAP and JSON codecs and mocked collaborators, for SOAP tests. */
final class SoapTestSupport {

    final ObjectMapper mapper = new ObjectMapper();
    final EngineProperties props = new EngineProperties();
    final RouteForgeProperties routeForgeProperties = new RouteForgeProperties();
    final RouteAuditWriter auditWriter = mock(RouteAuditWriter.class);
    final EngineMetrics metrics = mock(EngineMetrics.class);
    final ApplicationContext applicationContext = mock(ApplicationContext.class);
    final Map<String, Object> lastCtx = new HashMap<>();
    CredentialVerifier verifier = (partner, user, password) -> "test-password".equals(password);
    final OrchestrationEngine engine;

    @SuppressWarnings("unchecked")
    SoapTestSupport() {
        AuditPayloadSanitizer sanitizer = mock(AuditPayloadSanitizer.class);
        when(sanitizer.sanitizeHeaders(any())).thenReturn("{}");
        ObjectProvider<CredentialVerifier> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenAnswer(inv -> verifier);

        // A businessLogic step that echoes the decoded input back as the SOAP return value.
        OrchestrationBusinessLogic echo = ctx -> {
            lastCtx.clear();
            lastCtx.putAll(ctx);
            Map<?, ?> input = (Map<?, ?>) ctx.get("input");
            ctx.put(SoapCodec.RETURN_KEY, "OK~~" + input.get("data"));
        };
        when(applicationContext.getBean("echoReturn")).thenReturn(echo);

        ErrorEnvelopeFactory envelopes = new ErrorEnvelopeFactory(mapper);
        CipherRegistry ciphers = new CipherRegistry(List.of());
        SoapCodec soapCodec = new SoapCodec(routeForgeProperties, provider, metrics);
        engine = new OrchestrationEngine(
                new ExceptionTranslator(500), mock(ProducerTemplate.class), applicationContext,
                mock(JoltTransformer.class), mapper, props, envelopes, metrics,
                mock(StepCallAuditWriter.class), auditWriter, sanitizer, ciphers,
                mock(AuthTokenProvider.class), mock(ExecutorService.class), mock(PartnerResolverService.class),
                mock(OpenApiValidatorRegistry.class),
                new InboundCodecRegistry(List.of(soapCodec, new JsonCodec(mapper, props, envelopes, ciphers))));
    }

    RouteAuditRecord lastAudit() {
        ArgumentCaptor<RouteAuditRecord> captor = ArgumentCaptor.forClass(RouteAuditRecord.class);
        verify(auditWriter, atLeastOnce()).write(captor.capture());
        return captor.getValue();
    }

    static TransactionConfig miblRoute(SoapAuthConfig auth) {
        SoapRouteConfig soap = new SoapRouteConfig("web:MIBLProposalInfo", MIBL_PREFIXES, null,
                Map.of("data", "miblProposalData", "userName", "soapHeader/userName", "password", "soapHeader/password"),
                auth, MIBL_RESPONSE, null, null);
        return new TransactionConfig("mibl-proposal", "/ws/mibl/MiblWebSystem", "MIBL", null,
                List.of(OrchestrationStep.businessLogic("echo", "echoReturn")), null, null, "POST", null, null,
                TransactionConfig.CHANNEL_SOAP, soap);
    }

    static TransactionConfig marutiRoute() {
        SoapRouteConfig soap = new SoapRouteConfig("prop:MarutiProposalInfoNew", MARUTI_PREFIXES, null,
                Map.of("data", "prop:MarutiProposalStatusInfoNew", "userName", "prop:shIn/auth:userName",
                        "password", "prop:shIn/auth:password"),
                bodyCredentials(), MARUTI_RESPONSE, null, null);
        return new TransactionConfig("maruti-proposal-new", "/ws/maruti/MarutiWebserviceSystem", "MARUTI", null,
                List.of(OrchestrationStep.businessLogic("echo", "echoReturn")), null, null, "POST", null, null,
                TransactionConfig.CHANNEL_SOAP, soap);
    }

    static SoapAuthConfig bodyCredentials() {
        return new SoapAuthConfig(SoapAuthConfig.BODY_CREDENTIALS, "userName", "password", null);
    }
}
