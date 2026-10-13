package com.rsa.routeforge.config;


import com.rsa.routeforge.error.ErrorCode;
import com.rsa.routeforge.error.ErrorEnvelopeFactory;
import com.rsa.routeforge.error.OrchestrationException;
import com.rsa.routeforge.route.RoutesBuilder;
import org.apache.camel.CamelContext;
import org.apache.camel.builder.RouteBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Runtime route administration.
 * <p>
 * NOTE: these endpoints are not authenticated. They should be bound to the management port and put
 * behind authentication before this runs anywhere shared — anyone who can reach the port can remove
 * a live route.
 */
@RestController
@ConditionalOnProperty(prefix = "routeforge.admin", name = "enabled", havingValue = "true")
@RequestMapping("/admin/routes")
public class RouteAdminController {

    private static final Logger log = LoggerFactory.getLogger(RouteAdminController.class);

    private final CamelContext camelContext;
    private final OrchestrationEngine orchestrationEngine;
    private final RouteConfigLoader routeConfigLoader;
    private final ErrorEnvelopeFactory envelopes;

    public RouteAdminController(CamelContext camelContext, OrchestrationEngine orchestrationEngine, RouteConfigLoader routeConfigLoader, ErrorEnvelopeFactory envelopes) {
        this.camelContext = camelContext;
        this.orchestrationEngine = orchestrationEngine;
        this.routeConfigLoader = routeConfigLoader;
        this.envelopes = envelopes;
    }

    @PostMapping("/reload/{routeId}")
    public Map<String, Object> reload(@PathVariable String routeId) throws Exception {
        log.info("Admin: reload requested for routeId={}", routeId);

        // Load and validate BEFORE touching the running route. Previously the old route was stopped
        // and removed first, so a bad config left the route deleted and the endpoint dead.
        TransactionConfig cfg = routeConfigLoader.loadConfigs().stream()
                .filter(c -> c.routeId().equals(routeId))
                .findFirst()
                .orElseThrow(() -> new OrchestrationException(ErrorCode.CONFIG_ERROR,
                        "No valid route config found for routeId '" + routeId + "'"));

        if (camelContext.getRoute(routeId) != null) {
            camelContext.getRouteController().stopRoute(routeId);
            camelContext.removeRoute(routeId);
        }

        camelContext.addRoutes(new RouteBuilder() {
            @Override
            public void configure() {
                RoutesBuilder.defineRoute(this, cfg, orchestrationEngine);
            }
        });

        log.info("Admin: route {} reloaded (path={})", routeId, cfg.path());
        return envelopes.success(Map.of("routeId", routeId, "action", "reloaded", "path", cfg.path()),"");
    }

    @PostMapping("/remove/{routeId}")
    public Map<String, Object> remove(@PathVariable String routeId) throws Exception {
        log.info("Admin: remove requested for routeId={}", routeId);

        if (camelContext.getRoute(routeId) == null) {
            throw new OrchestrationException(ErrorCode.CONFIG_ERROR,
                    "No active route with id '" + routeId + "'");
        }
        camelContext.getRouteController().stopRoute(routeId);
        camelContext.removeRoute(routeId);

        log.warn("Admin: route {} REMOVED", routeId);
        return envelopes.success(Map.of("routeId", routeId, "action", "removed"),"");
    }
}
