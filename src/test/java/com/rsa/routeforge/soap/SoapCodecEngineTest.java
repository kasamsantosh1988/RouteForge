package com.rsa.routeforge.soap;

import com.rsa.routeforge.audit.RouteAuditRecord;
import com.rsa.routeforge.config.TransactionConfig;
import org.apache.camel.Exchange;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.support.DefaultExchange;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static com.rsa.routeforge.soap.SoapSamples.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

/** SOAP requests through the real engine: parse, authenticate, run steps, write the wrapper or a fault. */
class SoapCodecEngineTest {

    private final SoapTestSupport t = new SoapTestSupport();
    private final DefaultCamelContext camel = new DefaultCamelContext();

    @AfterEach
    void tearDown() {
        camel.stop();
    }

    @Test
    void miblProposalRunsStepsAndReturnsTheWrapper() {
        Exchange ex = exchange(MIBL_REQUEST);

        t.engine.execute(SoapTestSupport.miblRoute(SoapTestSupport.bodyCredentials()), ex);

        assertEquals(200, ex.getIn().getHeader(Exchange.HTTP_RESPONSE_CODE));
        assertEquals(200, ex.getIn().getHeader("X-Engine-Status"));
        assertEquals("text/xml; charset=utf-8", ex.getIn().getHeader(Exchange.CONTENT_TYPE));
        String xml = ex.getIn().getBody(String.class);
        assertTrue(xml.contains("<MIBLProposalInfoResponse xmlns=\"" + MIBL_NS + "\"><return xmlns=\"\">OK~~"
                + MIBL_DATA + "</return></MIBLProposalInfoResponse>"), xml);

        Map<?, ?> input = (Map<?, ?>) t.lastCtx.get("input");
        assertEquals(MIBL_DATA, input.get("data"));
        assertEquals("test-mibl", input.get("userName"));
        assertFalse(input.containsKey("password"), "the password never reaches a step");
        assertEquals("MIBL", t.lastCtx.get("partnerCode"));

        RouteAuditRecord audit = t.lastAudit();
        assertEquals(RouteAuditRecord.OUTCOME_SUCCESS, audit.getOutcome());
        assertEquals(xml, audit.getResponseBody());
        assertEquals("mibl-proposal", audit.getRouteId());
    }

    @Test
    void marutiProposalReturnsCdata() {
        Exchange ex = exchange(MARUTI_REQUEST);

        t.engine.execute(SoapTestSupport.marutiRoute(), ex);

        String xml = ex.getIn().getBody(String.class);
        assertTrue(xml.contains("<MarutiProposalInfoNewResponse xmlns=\"" + MARUTI_NS + "\">"
                + "<MarutiProposalInfoNewReturn><![CDATA[OK~~" + MARUTI_DATA + "]]></MarutiProposalInfoNewReturn>"), xml);
        assertEquals("test-maruti", ((Map<?, ?>) t.lastCtx.get("input")).get("userName"));
    }

    @Test
    void wrongPasswordIsAClientFaultAndNoStepRuns() {
        Exchange ex = exchange(MIBL_REQUEST.replace("test-password", "wrong"));

        t.engine.execute(SoapTestSupport.miblRoute(SoapTestSupport.bodyCredentials()), ex);

        assertEquals(500, ex.getIn().getHeader(Exchange.HTTP_RESPONSE_CODE), "SOAP 1.1 faults use HTTP 500");
        assertEquals(401, ex.getIn().getHeader("X-Engine-Status"));
        String xml = ex.getIn().getBody(String.class);
        assertTrue(xml.contains("<faultcode>soapenv:Client</faultcode><faultstring>Authentication failed.</faultstring>"), xml);
        assertTrue(xml.contains("<code>AUTHENTICATION_FAILED</code>"), xml);
        assertFalse(xml.contains("wrong"), "the sent password is never echoed");
        assertTrue(t.lastCtx.isEmpty(), "no step ran");

        RouteAuditRecord audit = t.lastAudit();
        assertEquals(RouteAuditRecord.OUTCOME_FAILURE, audit.getOutcome());
        assertEquals("AUTHENTICATION_FAILED", audit.getErrorCode());
        verify(t.metrics).recordSoapFault(eq("mibl-proposal"), eq("Client"), eq("AUTHENTICATION_FAILED"));
    }

    @Test
    void missingVerifierIsAServerFault() {
        t.verifier = null;
        Exchange ex = exchange(MIBL_REQUEST);

        t.engine.execute(SoapTestSupport.miblRoute(SoapTestSupport.bodyCredentials()), ex);

        String xml = ex.getIn().getBody(String.class);
        assertTrue(xml.contains("<faultcode>soapenv:Server</faultcode>"), xml);
        assertTrue(xml.contains("<code>CONFIG_ERROR</code>"), xml);
        assertTrue(xml.contains("<faultstring>An unexpected error occurred.</faultstring>"), xml);
    }

    @Test
    void verifierThatThrowsIsAServerFault() {
        t.verifier = (p, u, pw) -> { throw new IllegalStateException("db down"); };
        Exchange ex = exchange(MIBL_REQUEST);

        t.engine.execute(SoapTestSupport.miblRoute(SoapTestSupport.bodyCredentials()), ex);

        String xml = ex.getIn().getBody(String.class);
        assertTrue(xml.contains("<faultcode>soapenv:Server</faultcode>"), xml);
        assertFalse(xml.contains("db down"));
    }

    @Test
    void gatewayAuthRequiresTheConfiguredHeader() {
        TransactionConfig route = SoapTestSupport.miblRoute(new SoapAuthConfig(SoapAuthConfig.GATEWAY, null, null, "X-Apim-Subscription"));

        Exchange missing = exchange(MIBL_REQUEST);
        t.engine.execute(route, missing);
        assertTrue(missing.getIn().getBody(String.class).contains("AUTHENTICATION_FAILED"));

        Exchange present = exchange(MIBL_REQUEST);
        present.getIn().setHeader("X-Apim-Subscription", "abc");
        t.engine.execute(route, present);
        assertEquals(200, present.getIn().getHeader(Exchange.HTTP_RESPONSE_CODE));
        assertTrue(((Map<?, ?>) t.lastCtx.get("input")).containsKey("password"),
                "with gateway auth the password field is an ordinary field");
    }

    @Test
    void noAuthRunsWithoutAVerifier() {
        t.verifier = null;
        Exchange ex = exchange(MIBL_REQUEST);

        t.engine.execute(SoapTestSupport.miblRoute(null), ex);

        assertEquals(200, ex.getIn().getHeader(Exchange.HTTP_RESPONSE_CODE));
    }

    @Test
    void malformedXmlIsAClientFault() {
        Exchange ex = exchange("<soapenv:Envelope xmlns:soapenv=\"" + SoapRequest.SOAP11_NS + "\"><soapenv:Body><a>");

        t.engine.execute(SoapTestSupport.miblRoute(SoapTestSupport.bodyCredentials()), ex);

        String xml = ex.getIn().getBody(String.class);
        assertTrue(xml.contains("<faultcode>soapenv:Client</faultcode>"), xml);
        assertTrue(xml.contains("<code>SOAP_MALFORMED</code>"), xml);
        assertEquals("<soapenv:Envelope xmlns:soapenv=\"" + SoapRequest.SOAP11_NS + "\"><soapenv:Body><a>",
                t.lastAudit().getRequestBody(), "the rejected request is still audited");
    }

    @Test
    void routeWithoutSoapReturnSendsAnEmptyReturnNotTheContext() {
        TransactionConfig base = SoapTestSupport.miblRoute(null);
        TransactionConfig noSteps = new TransactionConfig(base.routeId(), base.path(), base.partnerCode(), null, null,
                null, null, "POST", null, null, base.channel(), base.soap());
        Exchange ex = exchange(MIBL_REQUEST);

        t.engine.execute(noSteps, ex);

        String xml = ex.getIn().getBody(String.class);
        assertTrue(xml.contains("<return xmlns=\"\"></return>"), xml);
        assertFalse(xml.contains("test-password"));
        assertFalse(xml.contains(MIBL_DATA));
    }

    private Exchange exchange(String body) {
        Exchange ex = new DefaultExchange(camel);
        ex.getIn().setBody(body);
        ex.getIn().setHeader(Exchange.CONTENT_TYPE, "text/xml;charset=UTF-8");
        return ex;
    }
}
