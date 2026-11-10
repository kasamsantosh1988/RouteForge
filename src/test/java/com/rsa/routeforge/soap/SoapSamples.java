package com.rsa.routeforge.soap;

import java.util.Map;

/**
 * SOAP envelopes shaped exactly like the Maruti (MI1.0) and MIBL UAT samples: same elements,
 * namespaces, prefixes and ordering. Credentials and data strings are synthetic; no real customer
 * data is used in tests.
 */
final class SoapSamples {

    static final String MARUTI_NS = "http://proposal.maruti.royalsundaram.com";
    static final String AUTH_NS = "http://auth.royalsundaram.com";
    static final String MIBL_NS = "http://WebService.vam.com/";

    static final Map<String, String> MARUTI_PREFIXES = Map.of("prop", MARUTI_NS, "auth", AUTH_NS);
    static final Map<String, String> MIBL_PREFIXES = Map.of("web", MIBL_NS);

    /** Maruti uses "~" between fields. */
    static final String MARUTI_DATA = "PROPTEST0001~31/12/2024~05:20:20 AM~31/12/2024~~VR~TEST MODEL 1.2L~0~N";

    /** MIBL uses "~~" between fields; an empty field is "~~~~". */
    static final String MIBL_DATA = "D~~P~~N~~12/10/2024~~16:22:32~~PROP100010~~~~I~~Mr.~~TEST~~CUSTOMER";

    static final String MARUTI_REQUEST = """
            <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:prop="http://proposal.maruti.royalsundaram.com" xmlns:auth="http://auth.royalsundaram.com">
            <soapenv:Header/>
            <soapenv:Body>
            <prop:MarutiProposalInfoNew>
            <prop:MarutiProposalStatusInfoNew>%s</prop:MarutiProposalStatusInfoNew>
            <prop:shIn>
            <auth:password>test-password</auth:password>
            <auth:userName>test-maruti</auth:userName>
            </prop:shIn>
            </prop:MarutiProposalInfoNew>
            </soapenv:Body>
            </soapenv:Envelope>
            """.formatted(MARUTI_DATA);

    /** Includes the SoapUI "Optional" comments and the stray "~~" after the data element seen in UAT. */
    static final String MIBL_REQUEST = """
            <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:web="http://WebService.vam.com/">
            <soapenv:Header/>
            <soapenv:Body>
            <web:MIBLProposalInfo>
            <soapHeader>
            <!--Optional:-->
            <password>test-password</password>
            <!--Optional:-->
            <userName>test-mibl</userName>
            </soapHeader>
            <miblProposalData>%s</miblProposalData>~~
            </web:MIBLProposalInfo>
            </soapenv:Body>
            </soapenv:Envelope>
            """.formatted(MIBL_DATA);

    static final SoapResponseConfig MARUTI_RESPONSE = new SoapResponseConfig(
            "prop:MarutiProposalInfoNewResponse", "MarutiProposalInfoNewReturn", true,
            "defaultNamespace", "cdata", "string");

    static final SoapResponseConfig MIBL_RESPONSE = new SoapResponseConfig(
            "web:MIBLProposalInfoResponse", "return", false,
            "defaultNamespace", "text", "string");

    private SoapSamples() {
    }
}
