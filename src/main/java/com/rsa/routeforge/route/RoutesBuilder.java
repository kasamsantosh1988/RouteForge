package com.rsa.routeforge.route;


import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsa.routeforge.audit.AuditExchangeProperties;
import com.rsa.routeforge.config.OrchestrationEngine;
import com.rsa.routeforge.config.OrchestrationStep;
import com.rsa.routeforge.config.RouteConfigLoader;
import com.rsa.routeforge.config.TransactionConfig;
import com.rsa.routeforge.error.ErrorEnvelopeFactory;
import com.rsa.routeforge.error.OrchestrationException;
import com.rsa.routeforge.soap.SoapEndpoint;
import com.rsa.routeforge.soap.SoapRouteConfig;
import com.rsa.routeforge.soap.SoapService;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.Exchange;
import org.apache.camel.builder.RouteBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;


@Slf4j
@Component
@ConditionalOnProperty(prefix = "routeforge", name = "enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnProperty(prefix = "routeforge.routes", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RoutesBuilder extends RouteBuilder {

    private static final Object APPLICATION_JSON ="application/json" ;
    @Autowired
    private RouteConfigLoader routeConfigLoader;
    @Autowired
    private OrchestrationEngine orchestrationEngine;
    @Autowired
    private Environment env;
    @Autowired
    private RouteForgeProperties routeForgeProperties;
    @Autowired
    private ResourceLoader resourceLoader;

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();


    @Override
    public void configure() {
        // Safety net. The engine catches Throwable so this should be unreachable, but without it a
        // failure that did escape would be rendered by Camel's DefaultHttpBinding as a raw Java
        // stack trace with content-type text/plain.
        onException(IllegalArgumentException.class)
                .handled(true)
                .setHeader(Exchange.HTTP_RESPONSE_CODE, constant(415))
                .setHeader(Exchange.CONTENT_TYPE, constant(APPLICATION_JSON))
                .setBody(simple("""
                        {
                          "status":415,
                          "message":"Unsupported Media Type;Content-Type must be application/json"
                        }
                        """));
        onException(OrchestrationException.class)
                .handled(true)
                .setHeader(Exchange.CONTENT_TYPE, constant(APPLICATION_JSON))
                .setHeader(Exchange.HTTP_RESPONSE_CODE, constant(400))
                .setBody(simple("""
                    {
                      "status_code":"400",
                      "error":{
                        "code":"INVALID_JSON",
                        "message":"Request body is not valid JSON",
                        "details":[]
                      },
                      "data":null
                    }
                    """));
        onException(Throwable.class)
                .handled(true)
                .log("Unhandled error escaped the orchestration engine: ${exception.stacktrace}")
                .setHeader(Exchange.CONTENT_TYPE, constant(APPLICATION_JSON))
                .setHeader(Exchange.HTTP_RESPONSE_CODE, constant(200))
                .setBody(constant(ErrorEnvelopeFactory.FALLBACK_ERROR_JSON));

        // SOAP operations that share a path become one endpoint; insertion order keeps startup logs stable.
        Map<String, List<TransactionConfig>> soapServices = new LinkedHashMap<>();
        for (TransactionConfig cfg : routeConfigLoader.loadConfigs()) {
            // Resolve all property placeholders in URIs before building the route
            TransactionConfig resolvedConfig = resolveUrisInConfig(cfg);
            if (resolvedConfig.isSoap()) {
                soapServices.computeIfAbsent(normalizePath(resolvedConfig.path()), k -> new ArrayList<>()).add(resolvedConfig);
            } else {
                defineRoute(this, resolvedConfig, orchestrationEngine);
            }
        }
        soapServices.forEach((path, operations) -> {
            SoapService service = new SoapService(path, operations);
            defineSoapService(this, service, new SoapEndpoint(service, orchestrationEngine,
                    routeForgeProperties.getSoap().limits(), resourceLoader));
            log.info("SOAP service {} with {} operation(s): {}", path, operations.size(),
                    operations.stream().map(TransactionConfig::routeId).toList());
        });
    }

    /**
     * One Camel route per SOAP service path. Unlike REST routes there is no JSON content-type
     * check and no method restriction: {@link SoapEndpoint} answers {@code GET ?wsdl} itself and
     * turns any other non-POST request into a SOAP fault.
     */
    public static void defineSoapService(RouteBuilder builder, SoapService service, SoapEndpoint endpoint) {
        builder.from("platform-http:" + service.path() + "?muteException=true")
                .routeId(service.routeId())
                .process(RoutesBuilder::captureInboundHttpMetadata)
                .process(endpoint::process);
    }

    private static String normalizePath(String path) {
        return path != null && path.endsWith("/") && path.length() > 1 ? path.substring(0, path.length() - 1) : path;
    }

    private TransactionConfig resolveUrisInConfig(TransactionConfig config) {
        List<OrchestrationStep> resolvedSteps = config.steps() != null
                ? resolveStepList(config.steps())
                : null;

        return new TransactionConfig(
                config.routeId(),
                config.path(),
                config.partnerCode(),
                config.requestJoltSpec(),
                resolvedSteps,
                config.responseJoltSpec(),
                config.encryptionProfile(),
                config.httpMethod(),
                config.responseJoltSpecRules(),
                config.openApiValidation(),
                config.channel(),
                resolveSoap(config.soap())
        );
    }

    /** Property placeholders are allowed in the WSDL location and public URL, e.g. ${pie.public-url}. */
    private SoapRouteConfig resolveSoap(SoapRouteConfig soap) {
        if (soap == null) {
            return null;
        }
        return new SoapRouteConfig(soap.operation(), soap.namespaces(), soap.lenientNamespaces(), soap.fields(),
                soap.response(),
                soap.wsdl() == null ? null : env.resolvePlaceholders(soap.wsdl()),
                soap.publicUrl() == null ? null : env.resolvePlaceholders(soap.publicUrl()));
    }

    /**
     * Recursively resolve URIs in a list of steps.
     */
    private List<OrchestrationStep> resolveStepList(List<OrchestrationStep> steps) {
        return steps.stream()
                .map(this::resolveStep)
                .toList();
    }

    /**
     * Resolve a single step: replace property placeholders in URI and recursively handle nested steps.
     */
    private OrchestrationStep resolveStep(OrchestrationStep step) {
        String resolvedUri = step.uri() != null
                ? env.resolvePlaceholders(step.uri())
                : null;

        if (resolvedUri != null && !resolvedUri.equals(step.uri())) {
            log.debug("Resolved URI for step '{}': {} → {}", step.id(), step.uri(), resolvedUri);
        }

        // Recursively resolve nested steps
        List<OrchestrationStep> resolvedThenSteps = step.thenSteps() != null
                ? resolveStepList(step.thenSteps())
                : null;

        List<OrchestrationStep> resolvedElseSteps = step.elseSteps() != null
                ? resolveStepList(step.elseSteps())
                : null;

        List<OrchestrationStep> resolvedBranches = step.branches() != null
                ? resolveStepList(step.branches())
                : null;

        // Create a new step with resolved values
        return new OrchestrationStep(
                step.id(),
                step.type(),
                resolvedUri,  // Use resolved URI
                step.method(),
                step.requestJoltSpec(),
                step.responseJoltSpec(),
                step.responseVariable(),
                step.useFullContext(),
                step.beanRef(),
                step.condition(),
                resolvedThenSteps,
                resolvedElseSteps,
                resolvedBranches,
                step.requiredFields(),
                step.errorCheck(),
                step.fieldValidations(),
                step.extraHeaders(),
                step.auth(),
                step.encryptionProfile()
        );
    }

    /**
     * Single definition of what a route looks like, shared with
     * {@code RouteAdminController.reload} so a reloaded route cannot drift from the one built at
     * startup — it previously omitted {@code removeHeaders}, letting inbound {@code CamelHttp*}
     * headers leak into the parsed input and into outbound calls.
     */
    public static void defineRoute(RouteBuilder builder, TransactionConfig cfg, OrchestrationEngine engine) {
        // muteException: defence in depth against Camel writing a stack trace to the caller.
        String uri = "platform-http:" + cfg.path() + "?muteException=true";
        if (cfg.httpMethod() != null && !cfg.httpMethod().isBlank()) {
            uri += "&httpMethodRestrict=" + cfg.httpMethod().toUpperCase();
        }
        builder.from(uri)
                .routeId(cfg.routeId())
                .process(RoutesBuilder::captureInboundHttpMetadata)
                .process((RoutesBuilder::jsonContentTypeValidator))
                .removeHeaders("CamelHttp*")
                .process(exchange -> engine.execute(cfg, exchange));
    }

    /**
     * Copies the real inbound method, URI and query string into exchange <em>properties</em>, for
     * the audit record.
     * <p>
     * This has to run before {@code removeHeaders("CamelHttp*")}, which is what makes it a separate
     * processor rather than something the engine reads for itself: by the time the engine runs,
     * {@code CamelHttpMethod} and friends are gone. Properties are used rather than renamed headers
     * because {@code removeHeaders} does not touch them, and because a header would then be
     * eligible to leak into {@code ctx.input} via {@code parseIncoming}.
     */
    private static void captureInboundHttpMetadata(Exchange exchange) {
        exchange.setProperty(AuditExchangeProperties.HTTP_METHOD,
                exchange.getIn().getHeader(Exchange.HTTP_METHOD, String.class));
        exchange.setProperty(AuditExchangeProperties.HTTP_URI,
                exchange.getIn().getHeader(Exchange.HTTP_URI, String.class));
        exchange.setProperty(AuditExchangeProperties.HTTP_QUERY,
                exchange.getIn().getHeader(Exchange.HTTP_QUERY, String.class));
    }

    private static void jsonContentTypeValidator(Exchange exchange) {
        String body = exchange.getIn().getBody(String.class);
        if (body == null || body.isBlank()) {
            return;
        }
        String contentType = exchange.getIn()
                .getHeader(Exchange.CONTENT_TYPE, String.class);
        if (contentType == null ||
                !contentType.toLowerCase().startsWith((String) APPLICATION_JSON)) {

            throw new IllegalArgumentException(
                    "Content-Type must be application/json");
        }
        try {
            OBJECT_MAPPER.readTree(body);
        } catch (Exception _) {
            throw new OrchestrationException( "INVALID_JSON", "Request body is not valid JSON", 400);
        }
    }
}
