package com.rsa.routeforge.soap;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static com.rsa.routeforge.soap.SoapSamples.*;
import static org.junit.jupiter.api.Assertions.*;

class SoapResponseWriterTest {

    private static final String DECL = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>";
    private static final String ENV_OPEN = "<soapenv:Envelope xmlns:soapenv=\"http://schemas.xmlsoap.org/soap/envelope/\""
            + " xmlns:xsd=\"http://www.w3.org/2001/XMLSchema\""
            + " xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"><soapenv:Body>";
    private static final String ENV_CLOSE = "</soapenv:Body></soapenv:Envelope>";

    @Test
    void marutiResponseIsDefaultNamespaceWithQualifiedReturnInCdata() {
        String inner = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                + "<MarutiOnlinePolicySubmissionResponse><status>1</status><Message>APPROVED</Message></MarutiOnlinePolicySubmissionResponse>";

        String xml = SoapResponseWriter.success(MARUTI_RESPONSE, MARUTI_PREFIXES, inner);

        assertEquals(DECL + ENV_OPEN
                + "<MarutiProposalInfoNewResponse xmlns=\"" + MARUTI_NS + "\">"
                + "<MarutiProposalInfoNewReturn><![CDATA[" + inner + "]]></MarutiProposalInfoNewReturn>"
                + "</MarutiProposalInfoNewResponse>" + ENV_CLOSE, xml);

        Element ret = returnElement(xml);
        assertEquals(MARUTI_NS, ret.getNamespaceURI());
        assertEquals(inner, ret.getTextContent());
    }

    @Test
    void miblResponseResetsNamespaceOnUnqualifiedReturn() {
        String value = "MMBL0001~~802186~~10/11/2026~~03:39:12 AM~~1~~MMBL0001";

        String xml = SoapResponseWriter.success(MIBL_RESPONSE, MIBL_PREFIXES, value);

        assertEquals(DECL + ENV_OPEN
                + "<MIBLProposalInfoResponse xmlns=\"" + MIBL_NS + "\">"
                + "<return xmlns=\"\">" + value + "</return>"
                + "</MIBLProposalInfoResponse>" + ENV_CLOSE, xml);

        Element ret = returnElement(xml);
        assertNull(ret.getNamespaceURI(), "return is in no namespace");
        assertEquals("return", ret.getLocalName());
        assertEquals(value, ret.getTextContent());
    }

    @Test
    void prefixedStyleUsesNs1() {
        SoapResponseConfig cfg = new SoapResponseConfig("web:MIBLProposalInfoResponse", "return", false,
                "prefixed", "text", "string");

        String xml = SoapResponseWriter.success(cfg, MIBL_PREFIXES, "v");

        assertTrue(xml.contains("<ns1:MIBLProposalInfoResponse xmlns:ns1=\"" + MIBL_NS + "\"><return>v</return></ns1:MIBLProposalInfoResponse>"), xml);
        Element ret = returnElement(xml);
        assertNull(ret.getNamespaceURI());
        assertEquals("v", ret.getTextContent());
    }

    @Test
    void textIsEscaped() {
        String value = "A & B <x> \"q\"";
        String xml = SoapResponseWriter.success(MIBL_RESPONSE, MIBL_PREFIXES, value);

        assertFalse(xml.contains("<x>"), xml);
        assertEquals(value, returnElement(xml).getTextContent());
    }

    @Test
    void cdataTerminatorInsideValueIsSplitSafely() {
        String value = "start]]>middle]]>end";
        String xml = SoapResponseWriter.success(MARUTI_RESPONSE, MARUTI_PREFIXES, value);

        assertEquals(value, returnElement(xml).getTextContent());
    }

    @Test
    void nullValueWritesEmptyReturn() {
        String xml = SoapResponseWriter.success(MIBL_RESPONSE, MIBL_PREFIXES, null);
        assertEquals("", returnElement(xml).getTextContent());
    }

    @Test
    void faultCarriesCodeStringAndDetailOnly() {
        String xml = SoapResponseWriter.fault("Client", "Invalid user name or password", "INVALID_CREDENTIALS", "trace-1");

        assertEquals(DECL + "<soapenv:Envelope xmlns:soapenv=\"http://schemas.xmlsoap.org/soap/envelope/\"><soapenv:Body>"
                + "<soapenv:Fault><faultcode>soapenv:Client</faultcode><faultstring>Invalid user name or password</faultstring>"
                + "<detail><code>INVALID_CREDENTIALS</code><traceId>trace-1</traceId></detail></soapenv:Fault>"
                + ENV_CLOSE, xml);
        assertFalse(xml.contains("hostname"));
        parse(xml);
    }

    @Test
    void faultStringIsEscaped() {
        String xml = SoapResponseWriter.fault("Server", "a < b & c", null, null);
        Document doc = parse(xml);
        assertEquals("a < b & c", doc.getElementsByTagName("faultstring").item(0).getTextContent());
        assertEquals(0, doc.getElementsByTagName("detail").getLength());
    }

    @Test
    void fallbackFaultIsWellFormed() {
        parse(SoapResponseWriter.FALLBACK_FAULT);
    }

    @Test
    void faultCodes() {
        assertEquals("VersionMismatch", SoapResponseWriter.faultCodeFor("SOAP_VERSION_MISMATCH", 400));
        assertEquals("Client", SoapResponseWriter.faultCodeFor("VALIDATION_FAILED", 400));
        assertEquals("Client", SoapResponseWriter.faultCodeFor("INVALID_CREDENTIALS", 401));
        assertEquals("Server", SoapResponseWriter.faultCodeFor("DOWNSTREAM_TIMEOUT", 504));
    }

    @Test
    void undeclaredWrapperPrefixIsAConfigError() {
        assertThrows(IllegalArgumentException.class,
                () -> SoapResponseWriter.success(MIBL_RESPONSE, Map.of(), "v"));
    }

    private static Element returnElement(String xml) {
        Element body = (Element) parse(xml).getDocumentElement().getElementsByTagNameNS(SoapRequest.SOAP11_NS, "Body").item(0);
        Element wrapper = firstElement(body);
        return firstElement(wrapper);
    }

    private static Element firstElement(Element parent) {
        for (var n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e) {
                return e;
            }
        }
        throw new AssertionError("no child element in " + parent.getNodeName());
    }

    private static Document parse(String xml) {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            return f.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new AssertionError("not well-formed: " + xml, e);
        }
    }
}
