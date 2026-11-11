package com.rsa.routeforge.soap;

import com.rsa.routeforge.config.TransactionConfig;
import com.rsa.routeforge.error.OrchestrationException;
import org.apache.camel.Exchange;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.support.DefaultExchange;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static com.rsa.routeforge.soap.SoapSamples.*;
import static org.junit.jupiter.api.Assertions.*;

/** Dispatch by body element and WSDL serving for one SOAP service path. */
class SoapEndpointTest {

    private static final String WSDL = """
            <wsdl:definitions xmlns:wsdl="http://schemas.xmlsoap.org/wsdl/" xmlns:wsdlsoap="http://schemas.xmlsoap.org/wsdl/soap/">
            <wsdl:service name="MiblWebSystemService"><wsdl:port name="MiblWebSystem">
            <wsdlsoap:address location="http://www.royalsundaram.net/MIBLPolicyServiceUAT/services/MiblWebSystem"/>
            </wsdl:port></wsdl:service></wsdl:definitions>
            """;

    private final DefaultCamelContext camel = new DefaultCamelContext();
    private final SoapTestSupport t = new SoapTestSupport(camel);

    @AfterEach
    void tearDown() {
        camel.stop();
    }

    @Test
    void postIsDispatchedByBodyElementAndRunThroughTheEngine() {
        SoapEndpoint endpoint = endpoint(service("https://pie.example/ws/mibl/MiblWebSystem"));
        Exchange ex = post(MIBL_REQUEST);

        endpoint.process(ex);

        assertEquals(200, ex.getIn().getHeader(Exchange.HTTP_RESPONSE_CODE));
        assertTrue(ex.getIn().getBody(String.class).contains("<return xmlns=\"\">OK~~" + MIBL_DATA + "</return>"));
        assertNull(ex.getIn().getHeader(Exchange.HTTP_METHOD), "inbound CamelHttp* headers are removed");
        assertEquals("mibl-proposal", t.lastAudit().getRouteId());
    }

    @Test
    void secondOperationOnTheSamePathIsChosenByItsElement() {
        SoapService service = service(null);
        String payment = MIBL_REQUEST.replace("MIBLProposalInfo", "MIBLPaymentInfo").replace("miblProposalData", "miblPaymentData");
        Exchange ex = post(payment);

        TransactionConfig chosen = endpoint(service).dispatch(ex, "POST");

        assertEquals("mibl-payment", chosen.routeId());
        assertNotNull(ex.getProperty(SoapExchange.REQUEST));
    }

    @Test
    void unknownOperationIsAFaultUnderTheServiceId() {
        SoapEndpoint endpoint = endpoint(service(null));
        Exchange ex = post(MIBL_REQUEST.replace("MIBLProposalInfo", "MIBLUnknownInfo"));

        endpoint.process(ex);

        String xml = ex.getIn().getBody(String.class);
        assertEquals(500, ex.getIn().getHeader(Exchange.HTTP_RESPONSE_CODE));
        assertTrue(xml.contains("<code>SOAP_UNKNOWN_OPERATION</code>"), xml);
        assertEquals("soap:/ws/mibl/MiblWebSystem", t.lastAudit().getRouteId());
    }

    @Test
    void jsonContentTypeIsRejected() {
        Exchange ex = post(MIBL_REQUEST);
        ex.getIn().setHeader(Exchange.CONTENT_TYPE, "application/json");

        endpoint(service(null)).dispatch(ex, "POST");

        OrchestrationException error = ex.getProperty(SoapExchange.DISPATCH_ERROR, OrchestrationException.class);
        assertEquals("SOAP_UNSUPPORTED_MEDIA_TYPE", error.getCode());
    }

    @Test
    void soap11AndSoap12ContentTypesAreAccepted() {
        for (String type : List.of("text/xml; charset=utf-8", "application/soap+xml")) {
            Exchange ex = post(MIBL_REQUEST);
            ex.getIn().setHeader(Exchange.CONTENT_TYPE, type);
            endpoint(service(null)).dispatch(ex, "POST");
            assertNull(ex.getProperty(SoapExchange.DISPATCH_ERROR), type);
        }
    }

    @Test
    void getWithoutWsdlIsAFault() {
        Exchange ex = new DefaultExchange(camel);
        ex.getIn().setHeader(Exchange.HTTP_METHOD, "GET");

        endpoint(service(null)).process(ex);

        assertTrue(ex.getIn().getBody(String.class).contains("<code>SOAP_MALFORMED</code>"));
    }

    @Test
    void wsdlIsServedWithThePublicAddress() {
        SoapEndpoint endpoint = endpoint(service("https://pie.example/ws/mibl/MiblWebSystem"));
        Exchange ex = new DefaultExchange(camel);
        ex.getIn().setHeader(Exchange.HTTP_METHOD, "GET");
        ex.getIn().setHeader(Exchange.HTTP_QUERY, "wsdl");

        endpoint.process(ex);

        String wsdl = ex.getIn().getBody(String.class);
        assertEquals(200, ex.getIn().getHeader(Exchange.HTTP_RESPONSE_CODE));
        assertEquals("text/xml; charset=utf-8", ex.getIn().getHeader(Exchange.CONTENT_TYPE));
        assertTrue(wsdl.contains("<wsdlsoap:address location=\"https://pie.example/ws/mibl/MiblWebSystem\"/>"), wsdl);
        assertFalse(wsdl.contains("royalsundaram.net"));
    }

    @Test
    void wsdlWithoutPublicUrlIsServedUnchanged() {
        Exchange ex = new DefaultExchange(camel);
        ex.getIn().setHeader(Exchange.HTTP_METHOD, "GET");
        ex.getIn().setHeader(Exchange.HTTP_QUERY, "WSDL");

        endpoint(service(null)).process(ex);

        assertEquals(WSDL, ex.getIn().getBody(String.class));
    }

    @Test
    void missingWsdlIs404() {
        SoapEndpoint endpoint = new SoapEndpoint(service(null), t.engine, SoapLimits.DEFAULTS, new FixedResourceLoader(missing()));
        Exchange ex = new DefaultExchange(camel);
        ex.getIn().setHeader(Exchange.HTTP_METHOD, "GET");
        ex.getIn().setHeader(Exchange.HTTP_QUERY, "wsdl");

        endpoint.process(ex);

        assertEquals(404, ex.getIn().getHeader(Exchange.HTTP_RESPONSE_CODE));
    }

    @Test
    void wsdlQueryDetection() {
        assertTrue(SoapEndpoint.isWsdlQuery("wsdl"));
        assertTrue(SoapEndpoint.isWsdlQuery("WSDL"));
        assertTrue(SoapEndpoint.isWsdlQuery("a=1&wsdl"));
        assertTrue(SoapEndpoint.isWsdlQuery("wsdl="));
        assertFalse(SoapEndpoint.isWsdlQuery("xsd=1"));
        assertFalse(SoapEndpoint.isWsdlQuery(null));
    }

    private SoapService service(String publicUrl) {
        TransactionConfig proposal = withWsdl(SoapTestSupport.miblRoute(), publicUrl);
        SoapRouteConfig p = proposal.soap();
        SoapRouteConfig paymentSoap = new SoapRouteConfig("web:MIBLPaymentInfo", p.namespaces(), null,
                Map.of("data", "miblPaymentData", "userName", "soapHeader/userName", "password", "soapHeader/password"),
                new SoapResponseConfig("web:MIBLPaymentInfoResponse", "return", false, null, null, null),
                p.wsdl(), p.publicUrl());
        TransactionConfig payment = new TransactionConfig("mibl-payment", proposal.path(), "MIBL", null,
                proposal.steps(), null, null, "POST", null, null, TransactionConfig.CHANNEL_SOAP, paymentSoap);
        return new SoapService(proposal.path(), List.of(proposal, payment));
    }

    private static TransactionConfig withWsdl(TransactionConfig cfg, String publicUrl) {
        SoapRouteConfig s = cfg.soap();
        SoapRouteConfig soap = new SoapRouteConfig(s.operation(), s.namespaces(), s.lenientNamespaces(), s.fields(),
                s.response(), "classpath:mibl.wsdl", publicUrl);
        return new TransactionConfig(cfg.routeId(), cfg.path(), cfg.partnerCode(), null, cfg.steps(), null, null,
                "POST", null, null, cfg.channel(), soap);
    }

    private SoapEndpoint endpoint(SoapService service) {
        ResourceLoader loader = new FixedResourceLoader(new ByteArrayResource(WSDL.getBytes(StandardCharsets.UTF_8)));
        return new SoapEndpoint(service, t.engine, SoapLimits.DEFAULTS, loader);
    }

    private static Resource missing() {
        return new ByteArrayResource(new byte[0]) {
            @Override
            public boolean exists() {
                return false;
            }
        };
    }

    /** Answers every location with the same resource. */
    private record FixedResourceLoader(Resource resource) implements ResourceLoader {
        @Override
        public Resource getResource(String location) {
            return resource;
        }

        @Override
        public ClassLoader getClassLoader() {
            return getClass().getClassLoader();
        }
    }

    private Exchange post(String body) {
        Exchange ex = new DefaultExchange(camel);
        ex.getIn().setBody(body);
        ex.getIn().setHeader(Exchange.HTTP_METHOD, "POST");
        ex.getIn().setHeader(Exchange.CONTENT_TYPE, "text/xml;charset=UTF-8");
        return ex;
    }
}
