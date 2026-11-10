package com.rsa.routeforge.soap;

import com.rsa.routeforge.error.ErrorCode;
import com.rsa.routeforge.error.OrchestrationException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.rsa.routeforge.soap.SoapSamples.*;
import static org.junit.jupiter.api.Assertions.*;

class SoapRequestTest {

    private static final String ENV_OPEN = "<soapenv:Envelope xmlns:soapenv=\"http://schemas.xmlsoap.org/soap/envelope/\"><soapenv:Body>";
    private static final String ENV_CLOSE = "</soapenv:Body></soapenv:Envelope>";

    // ------------------------------------------------------------------ partner samples

    @Test
    void marutiOperationAndFieldsResolveWithQualifiedNamespaces() {
        SoapRequest request = SoapRequest.parse(MARUTI_REQUEST, SoapLimits.DEFAULTS);

        assertEquals(MARUTI_NS, request.operationName().getNamespaceURI());
        assertEquals("MarutiProposalInfoNew", request.operationName().getLocalPart());
        assertEquals(MARUTI_DATA, request.field("prop:MarutiProposalStatusInfoNew", MARUTI_PREFIXES, false));
        assertEquals("test-maruti", request.field("prop:shIn/auth:userName", MARUTI_PREFIXES, false));
        assertEquals("test-password", request.field("prop:shIn/auth:password", MARUTI_PREFIXES, false));
    }

    @Test
    void miblFieldsAreUnqualifiedAndStrayTextIsIgnored() {
        SoapRequest request = SoapRequest.parse(MIBL_REQUEST, SoapLimits.DEFAULTS);

        assertEquals(MIBL_NS, request.operationName().getNamespaceURI());
        assertEquals("MIBLProposalInfo", request.operationName().getLocalPart());
        assertEquals(MIBL_DATA, request.field("miblProposalData", MIBL_PREFIXES, false),
                "the data string is passed through exactly, including empty ~~~~ fields");
        assertEquals("test-mibl", request.field("soapHeader/userName", MIBL_PREFIXES, false));
        assertEquals("test-password", request.field("soapHeader/password", MIBL_PREFIXES, false));
    }

    @Test
    void strictPathsRequireTheRightNamespaceAndLenientPathsDoNot() {
        SoapRequest request = SoapRequest.parse(MARUTI_REQUEST, SoapLimits.DEFAULTS);

        // Unprefixed means "no namespace": Maruti's children are qualified, so no match when strict.
        assertNull(request.field("MarutiProposalStatusInfoNew", MARUTI_PREFIXES, false));
        assertEquals(MARUTI_DATA, request.field("MarutiProposalStatusInfoNew", MARUTI_PREFIXES, true));
    }

    @Test
    void wirePrefixesDoNotNeedToMatchConfigPrefixes() {
        String xml = MARUTI_REQUEST.replace("prop:", "p1:").replace("xmlns:prop=", "xmlns:p1=")
                .replace("auth:", "a9:").replace("xmlns:auth=", "xmlns:a9=");
        SoapRequest request = SoapRequest.parse(xml, SoapLimits.DEFAULTS);

        assertEquals("test-maruti", request.field("prop:shIn/auth:userName", MARUTI_PREFIXES, false));
    }

    @Test
    void bodyAsMapCollectsRepeatedElementsAndNil() {
        String xml = ENV_OPEN
                + "<r:MarutiReconInfoBean xmlns:r=\"urn:r\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">"
                + "<policyData><PROPOSALNO>P1</PROPOSALNO><REMARKS xsi:nil=\"true\"/></policyData>"
                + "<policyData><PROPOSALNO>P2</PROPOSALNO><REMARKS>ok</REMARKS></policyData>"
                + "</r:MarutiReconInfoBean>" + ENV_CLOSE;
        SoapRequest request = SoapRequest.parse(xml, SoapLimits.DEFAULTS);

        Map<String, Object> body = request.bodyAsMap();
        List<?> rows = (List<?>) body.get("policyData");
        assertEquals(2, rows.size());
        Map<?, ?> first = (Map<?, ?>) rows.getFirst();
        assertEquals("P1", first.get("PROPOSALNO"));
        assertTrue(first.containsKey("REMARKS"));
        assertNull(first.get("REMARKS"));
        assertEquals(List.of("P1", "P2"), request.field("policyData/PROPOSALNO", Map.of(), false));
    }

    @Test
    void escapedTextAndCdataAreUnwrapped() {
        String xml = ENV_OPEN + "<op xmlns=\"urn:x\"><a>A &amp; B &lt;C&gt;</a><b><![CDATA[<x>1</x>]]></b></op>" + ENV_CLOSE;
        SoapRequest request = SoapRequest.parse(xml, SoapLimits.DEFAULTS);
        Map<String, String> ns = Map.of("x", "urn:x");

        assertEquals("A & B <C>", request.field("x:a", ns, false));
        assertEquals("<x>1</x>", request.field("x:b", ns, false));
    }

    @Test
    void missingFieldIsNull() {
        SoapRequest request = SoapRequest.parse(MIBL_REQUEST, SoapLimits.DEFAULTS);
        assertNull(request.field("soapHeader/token", MIBL_PREFIXES, false));
    }

    // ------------------------------------------------------------------ envelope rules

    @Test
    void soap12IsAVersionMismatch() {
        String xml = "<e:Envelope xmlns:e=\"http://www.w3.org/2003/05/soap-envelope\"><e:Body><op/></e:Body></e:Envelope>";
        assertCode(ErrorCode.SOAP_VERSION_MISMATCH, xml);
    }

    @Test
    void nonEnvelopeRootIsMalformed() {
        assertCode(ErrorCode.SOAP_MALFORMED, "<op/>");
    }

    @Test
    void emptyBodyIsMalformed() {
        assertCode(ErrorCode.SOAP_MALFORMED, ENV_OPEN + ENV_CLOSE);
    }

    @Test
    void twoOperationsInBodyIsMalformed() {
        assertCode(ErrorCode.SOAP_MALFORMED, ENV_OPEN + "<a/><b/>" + ENV_CLOSE);
    }

    @Test
    void notWellFormedIsMalformed() {
        assertCode(ErrorCode.SOAP_MALFORMED, ENV_OPEN + "<a>" + ENV_CLOSE);
        assertCode(ErrorCode.SOAP_MALFORMED, "{\"json\":true}");
        assertCode(ErrorCode.SOAP_MALFORMED, "  ");
    }

    // ------------------------------------------------------------------ attacks and limits

    @Test
    void externalEntityIsRejected() {
        String xxe = "<?xml version=\"1.0\"?>"
                + "<!DOCTYPE foo [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]>"
                + ENV_OPEN + "<op>&xxe;</op>" + ENV_CLOSE;
        OrchestrationException e = assertCode(ErrorCode.SOAP_MALFORMED, xxe);
        assertFalse(e.getMessage().contains("root:"), "file contents never reach the message");
    }

    @Test
    void entityExpansionIsRejected() {
        String bomb = "<?xml version=\"1.0\"?>"
                + "<!DOCTYPE lolz [<!ENTITY lol \"lol\"><!ENTITY lol2 \"&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;\">]>"
                + ENV_OPEN + "<op>&lol2;</op>" + ENV_CLOSE;
        assertCode(ErrorCode.SOAP_MALFORMED, bomb);
    }

    @Test
    void anyDoctypeIsRejected() {
        assertCode(ErrorCode.SOAP_MALFORMED, "<!DOCTYPE x>" + ENV_OPEN + "<op/>" + ENV_CLOSE);
    }

    @Test
    void sizeLimitIsEnforced() {
        SoapLimits small = new SoapLimits(100, 32, 1000);
        String xml = ENV_OPEN + "<op>" + "x".repeat(200) + "</op>" + ENV_CLOSE;
        assertThrowsCode(ErrorCode.SOAP_REQUEST_TOO_LARGE, () -> SoapRequest.parse(xml, small));
    }

    @Test
    void depthLimitIsEnforced() {
        SoapLimits shallow = new SoapLimits(1_000_000, 5, 1000);
        String xml = ENV_OPEN + "<a><b><c><d/></c></b></a>" + ENV_CLOSE;
        assertThrowsCode(ErrorCode.SOAP_REQUEST_TOO_LARGE, () -> SoapRequest.parse(xml, shallow));
    }

    @Test
    void elementCountLimitIsEnforced() {
        SoapLimits few = new SoapLimits(1_000_000, 32, 10);
        String xml = ENV_OPEN + "<op>" + "<i/>".repeat(20) + "</op>" + ENV_CLOSE;
        assertThrowsCode(ErrorCode.SOAP_REQUEST_TOO_LARGE, () -> SoapRequest.parse(xml, few));
    }

    @Test
    void clientMessageNeverEchoesTheRequest() {
        OrchestrationException e = assertCode(ErrorCode.SOAP_MALFORMED, ENV_OPEN + "<secret-value" + ENV_CLOSE);
        assertEquals(ErrorCode.SOAP_MALFORMED.clientMessage(), e.getMessage());
    }

    private static OrchestrationException assertCode(ErrorCode expected, String xml) {
        return assertThrowsCode(expected, () -> SoapRequest.parse(xml, SoapLimits.DEFAULTS));
    }

    private static OrchestrationException assertThrowsCode(ErrorCode expected, org.junit.jupiter.api.function.Executable call) {
        OrchestrationException e = assertThrows(OrchestrationException.class, call);
        assertEquals(expected.code(), e.getCode(), () -> "internal detail: " + e.internalDetail());
        return e;
    }
}
