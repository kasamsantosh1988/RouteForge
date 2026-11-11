package com.rsa.routeforge.soap;

import com.rsa.routeforge.audit.RouteAuditRecord;
import org.apache.camel.Exchange;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.support.DefaultExchange;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.rsa.routeforge.soap.SoapSamples.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

/**
 * SOAP requests through the real engine: parse, run the route's steps (including a login check as
 * a {@code call} step), write the wrapper or a fault.
 */
class SoapCodecEngineTest {

    private final DefaultCamelContext camel = new DefaultCamelContext();
    private final SoapTestSupport t = new SoapTestSupport(camel);

    @AfterEach
    void tearDown() {
        camel.stop();
    }

    @Test
    void miblProposalChecksLoginByStepAndReturnsTheWrapper() {
        Exchange ex = exchange(MIBL_REQUEST);

        t.engine.execute(SoapTestSupport.miblRoute(), ex);

        assertEquals(200, ex.getIn().getHeader(Exchange.HTTP_RESPONSE_CODE));
        assertEquals(200, ex.getIn().getHeader("X-Engine-Status"));
        assertEquals("text/xml; charset=utf-8", ex.getIn().getHeader(Exchange.CONTENT_TYPE));
        String xml = ex.getIn().getBody(String.class);
        assertTrue(xml.contains("<MIBLProposalInfoResponse xmlns=\"" + MIBL_NS + "\"><return xmlns=\"\">OK~~"
                + MIBL_DATA + "</return></MIBLProposalInfoResponse>"), xml);

        // The credentials API received the login exactly as the partner sent it.
        assertEquals(1, t.credentialRequests.size());
        assertEquals("test-mibl", t.credentialRequests.getFirst().get("userName"));
        assertEquals("test-password", t.credentialRequests.getFirst().get("password"));

        Map<?, ?> input = (Map<?, ?>) t.lastCtx.get("input");
        assertEquals(MIBL_DATA, input.get("data"));
        assertEquals("test-mibl", input.get("userName"));
        assertEquals("MIBL", t.lastCtx.get("partnerCode"));
        assertEquals(Boolean.TRUE, ((Map<?, ?>) t.lastCtx.get("login")).get("valid"));

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
        assertEquals("test-maruti", t.credentialRequests.getFirst().get("userName"));
    }

    @Test
    void loginRejectedByTheCredentialsApiIsAClientFaultAndLaterStepsDoNotRun() {
        Exchange ex = exchange(MIBL_REQUEST.replace("test-password", "wrong"));

        t.engine.execute(SoapTestSupport.miblRoute(), ex);

        assertEquals(500, ex.getIn().getHeader(Exchange.HTTP_RESPONSE_CODE), "SOAP 1.1 faults use HTTP 500");
        assertEquals(401, ex.getIn().getHeader("X-Engine-Status"));
        String xml = ex.getIn().getBody(String.class);
        assertTrue(xml.contains("<faultcode>soapenv:Client</faultcode><faultstring>Invalid user name or password</faultstring>"), xml);
        assertTrue(xml.contains("<code>INVALID_CREDENTIALS</code>"), xml);
        assertFalse(xml.contains("wrong"), "the sent password is never echoed");
        assertTrue(t.lastCtx.isEmpty(), "steps after the login check did not run");

        RouteAuditRecord audit = t.lastAudit();
        assertEquals(RouteAuditRecord.OUTCOME_FAILURE, audit.getOutcome());
        assertEquals("INVALID_CREDENTIALS", audit.getErrorCode());
        assertEquals("verifyLogin", audit.getFailedStep());
        verify(t.metrics).recordSoapFault(eq("mibl-proposal"), eq("Client"), eq("INVALID_CREDENTIALS"));
    }

    @Test
    void partnerBehindApimNeedsNoLoginStep() {
        Exchange ex = exchange(MIBL_REQUEST);

        t.engine.execute(SoapTestSupport.miblRoute(List.of(SoapTestSupport.echoStep())), ex);

        assertEquals(200, ex.getIn().getHeader(Exchange.HTTP_RESPONSE_CODE));
        assertTrue(t.credentialRequests.isEmpty(), "the engine itself never checks credentials");
    }

    @Test
    void malformedXmlIsAClientFault() {
        Exchange ex = exchange("<soapenv:Envelope xmlns:soapenv=\"" + SoapRequest.SOAP11_NS + "\"><soapenv:Body><a>");

        t.engine.execute(SoapTestSupport.miblRoute(), ex);

        String xml = ex.getIn().getBody(String.class);
        assertTrue(xml.contains("<faultcode>soapenv:Client</faultcode>"), xml);
        assertTrue(xml.contains("<code>SOAP_MALFORMED</code>"), xml);
        assertTrue(t.credentialRequests.isEmpty());
        assertEquals("<soapenv:Envelope xmlns:soapenv=\"" + SoapRequest.SOAP11_NS + "\"><soapenv:Body><a>",
                t.lastAudit().getRequestBody(), "the rejected request is still audited");
    }

    @Test
    void routeWithoutSoapReturnSendsAnEmptyReturnNotTheContext() {
        Exchange ex = exchange(MIBL_REQUEST);

        t.engine.execute(SoapTestSupport.miblRoute(null), ex);

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
