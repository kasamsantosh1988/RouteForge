package com.rsa.routeforge.soap;

import com.rsa.routeforge.config.OrchestrationEngine;
import com.rsa.routeforge.config.TransactionConfig;
import com.rsa.routeforge.error.ErrorCode;
import com.rsa.routeforge.error.OrchestrationException;
import org.apache.camel.Exchange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The HTTP endpoint of one {@link SoapService}.
 * <ul>
 *   <li>{@code GET <path>?wsdl}: the service's WSDL, with its {@code soap:address location} set to
 *       {@code publicUrl}.</li>
 *   <li>{@code POST <path>}: parse the envelope, pick the operation from the body element, and run
 *       it through the engine. A request no operation can take (wrong method or content type,
 *       malformed XML, unknown operation) still goes through the engine, under the service's own
 *       config, so it is answered with a SOAP fault and audited like any other failure.</li>
 * </ul>
 */
public class SoapEndpoint {

    private static final Logger log = LoggerFactory.getLogger(SoapEndpoint.class);

    private static final Pattern ADDRESS_LOCATION =
            Pattern.compile("(<(?:[\\w.-]+:)?address\\b[^>]*?\\blocation\\s*=\\s*\")[^\"]*(\")");

    private final SoapService service;
    private final OrchestrationEngine engine;
    private final SoapLimits limits;
    private final ResourceLoader resources;
    private volatile String wsdlCache;

    public SoapEndpoint(SoapService service, OrchestrationEngine engine, SoapLimits limits, ResourceLoader resources) {
        this.service = service;
        this.engine = engine;
        this.limits = limits;
        this.resources = resources;
    }

    public void process(Exchange exchange) {
        String method = exchange.getIn().getHeader(Exchange.HTTP_METHOD, String.class);
        String query = exchange.getIn().getHeader(Exchange.HTTP_QUERY, String.class);
        if ("GET".equalsIgnoreCase(method) && isWsdlQuery(query)) {
            serveWsdl(exchange);
            return;
        }
        // As on REST routes: inbound CamelHttp* headers must not leak into ctx.input or downstream calls.
        exchange.getIn().removeHeaders("CamelHttp*");
        TransactionConfig operation = dispatch(exchange, method);
        engine.execute(operation, exchange);
    }

    /**
     * Chooses the operation. Never throws: a failure is stored on the exchange for the codec and the
     * service config is returned.
     */
    TransactionConfig dispatch(Exchange exchange, String method) {
        try {
            if (!"POST".equalsIgnoreCase(method)) {
                throw new OrchestrationException(ErrorCode.SOAP_MALFORMED, "HTTP method " + method + " on a SOAP endpoint");
            }
            String contentType = exchange.getIn().getHeader(Exchange.CONTENT_TYPE, String.class);
            if (contentType != null && !contentType.toLowerCase(Locale.ROOT).contains("xml")) {
                throw new OrchestrationException(ErrorCode.SOAP_UNSUPPORTED_MEDIA_TYPE, "Content-Type " + contentType);
            }
            String raw = exchange.getIn().getBody(String.class);
            exchange.getIn().setBody(raw); // a stream body is now a String the engine can read again
            SoapRequest request = SoapRequest.parse(raw, limits);
            TransactionConfig operation = service.find(request.operationName());
            if (operation == null) {
                throw new OrchestrationException(ErrorCode.SOAP_UNKNOWN_OPERATION,
                        "no operation for " + request.operationName() + " on " + service.path());
            }
            exchange.setProperty(SoapExchange.REQUEST, request);
            return operation;
        } catch (OrchestrationException e) {
            exchange.setProperty(SoapExchange.DISPATCH_ERROR, e);
        } catch (RuntimeException e) {
            exchange.setProperty(SoapExchange.DISPATCH_ERROR,
                    new OrchestrationException(ErrorCode.SOAP_MALFORMED, "dispatch failed: " + e.getClass().getSimpleName(), e));
        }
        return service.serviceConfig();
    }

    void serveWsdl(Exchange exchange) {
        exchange.getIn().removeHeaders("CamelHttp*");
        String wsdl;
        try {
            wsdl = wsdl();
        } catch (Exception e) {
            log.error("WSDL for SOAP service {} could not be read from {}", service.path(), service.wsdl(), e);
            wsdl = null;
        }
        if (wsdl == null) {
            exchange.getIn().setBody("WSDL not available");
            exchange.getIn().setHeader(Exchange.CONTENT_TYPE, "text/plain; charset=utf-8");
            exchange.getIn().setHeader(Exchange.HTTP_RESPONSE_CODE, 404);
            return;
        }
        exchange.getIn().setBody(wsdl);
        exchange.getIn().setHeader(Exchange.CONTENT_TYPE, "text/xml; charset=utf-8");
        exchange.getIn().setHeader(Exchange.HTTP_RESPONSE_CODE, 200);
    }

    private String wsdl() throws Exception {
        if (service.wsdl() == null || service.wsdl().isBlank()) {
            return null;
        }
        String cached = wsdlCache;
        if (cached == null) {
            Resource resource = resources.getResource(service.wsdl());
            if (!resource.exists()) {
                return null;
            }
            String text;
            try (var in = resource.getInputStream()) {
                text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            cached = rewriteAddress(text, service.publicUrl());
            wsdlCache = cached;
        }
        return cached;
    }

    /** Sets every {@code <*:address location="...">} to the public URL, when one is configured. */
    static String rewriteAddress(String wsdl, String publicUrl) {
        if (publicUrl == null || publicUrl.isBlank()) {
            return wsdl;
        }
        Matcher m = ADDRESS_LOCATION.matcher(wsdl);
        return m.replaceAll(match -> Matcher.quoteReplacement(match.group(1) + publicUrl + match.group(2)));
    }

    static boolean isWsdlQuery(String query) {
        if (query == null) {
            return false;
        }
        for (String part : query.split("&")) {
            String name = part.contains("=") ? part.substring(0, part.indexOf('=')) : part;
            if ("wsdl".equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }
}
