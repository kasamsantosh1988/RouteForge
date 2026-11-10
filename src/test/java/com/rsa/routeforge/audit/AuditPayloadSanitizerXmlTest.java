package com.rsa.routeforge.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsa.routeforge.config.EngineProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Credentials in SOAP payloads never reach an audit row; JSON payload handling is unchanged. */
class AuditPayloadSanitizerXmlTest {

    private static final String MIBL = """
            <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:web="http://WebService.vam.com/">
            <soapenv:Body><web:MIBLProposalInfo><soapHeader>
            <password>s3cret</password>
            <userName>test-mibl</userName>
            </soapHeader><miblProposalData>D~~P~~N</miblProposalData></web:MIBLProposalInfo></soapenv:Body></soapenv:Envelope>""";

    private static final String MARUTI = """
            <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:prop="urn:p" xmlns:auth="urn:a">
            <soapenv:Body><prop:MarutiProposalInfoNew><prop:MarutiProposalStatusInfoNew>A~B</prop:MarutiProposalStatusInfoNew>
            <prop:shIn><auth:password>s3cret</auth:password><auth:userName>test-maruti</auth:userName></prop:shIn>
            </prop:MarutiProposalInfoNew></soapenv:Body></soapenv:Envelope>""";

    private final EngineProperties props = new EngineProperties();
    private final AuditPayloadSanitizer sanitizer = new AuditPayloadSanitizer(props, new ObjectMapper());

    @Test
    void unprefixedPasswordIsMaskedWithoutAnyConfig() {
        String out = sanitizer.sanitizeBody(MIBL);
        assertFalse(out.contains("s3cret"), out);
        assertTrue(out.contains("<password>***</password>"), out);
        assertTrue(out.contains("<userName>test-mibl</userName>"));
        assertTrue(out.contains("D~~P~~N"));
    }

    @Test
    void prefixedPasswordIsMasked() {
        String out = sanitizer.sanitizeBody(MARUTI);
        assertFalse(out.contains("s3cret"), out);
        assertTrue(out.contains("<auth:password>***</auth:password>"), out);
    }

    @Test
    void configuredFieldsAreMaskedInXmlTooAndXmlIsNotWithheld() {
        props.getAudit().setMaskFields(List.of("userName"));
        String out = sanitizer.sanitizeBody(MARUTI);
        assertTrue(out.contains("<auth:userName>***</auth:userName>"), out);
        assertTrue(out.contains("<auth:password>***</auth:password>"), out);
        assertNotEquals(AuditPayloadSanitizer.UNPARSEABLE_WITHHELD, out);
    }

    @Test
    void malformedXmlIsStillMasked() {
        String out = sanitizer.sanitizeBody("<a><password>s3cret</password><b>");
        assertEquals("<a><password>***</password><b>", out);
    }

    @Test
    void cdataAndAttributesAreHandled() {
        String out = sanitizer.sanitizeBody("<x><Password type=\"t\"><![CDATA[s3cret]]></Password></x>");
        assertEquals("<x><Password type=\"t\">***</Password></x>", out);
    }

    @Test
    void jsonWithoutMaskFieldsIsUntouchedAsBefore() {
        String json = "{\"password\":\"s3cret\",\"a\":1}";
        assertEquals(json, sanitizer.sanitizeBody(json));
    }

    @Test
    void jsonWithMaskFieldsIsMaskedAsBefore() {
        props.getAudit().setMaskFields(List.of("password"));
        assertEquals("{\"password\":\"***\",\"a\":1}", sanitizer.sanitizeBody("{\"password\":\"s3cret\",\"a\":1}"));
        assertEquals(AuditPayloadSanitizer.UNPARSEABLE_WITHHELD, sanitizer.sanitizeBody("not json"));
    }
}
