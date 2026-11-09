package com.rsa.routeforge.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsa.routeforge.route.RouteForgeProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.*;

@Component
public class RouteConfigLoader {

    private static final Logger log = LoggerFactory.getLogger(RouteConfigLoader.class);
    //private static final String ROUTES_DIR_PATTERN = "classpath:bimasugamroutes_config/*.json";
    private final ObjectMapper objectMapper;
    private final RouteConfigValidator validator;
    private final EngineProperties engineProperties;
    private final RouteForgeProperties routeForgeProperties;

    public RouteConfigLoader(ObjectMapper objectMapper, RouteConfigValidator validator, EngineProperties engineProperties, RouteForgeProperties routeForgeProperties) {
        this.objectMapper = objectMapper;
        this.validator = validator;
        this.engineProperties = engineProperties;
        this.routeForgeProperties = routeForgeProperties;
    }

    public List<TransactionConfig> loadConfigs() {
        if (!routeForgeProperties.isEnabled() || !routeForgeProperties.getRoutes().isEnabled()) {
            log.info("RouteForge route loading is disabled");
            return List.of();
        }
        List<TransactionConfig> configs = new ArrayList<>();
        Set<String> seenIds = new HashSet<>();
        Map<String, String> seenPaths = new HashMap<>(); // path -> filename that claimed it
        // Every problem across every file is collected, so one startup failure reports them all.
        List<String> problems = new ArrayList<>();

        validator.resetBeanRefs();
        String routesLocation = routeForgeProperties.getRoutes().getLocation();
        if (routesLocation == null) {
            log.warn("No routes location specified in application.properties");
            return configs;
        }
        try {
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            Resource[] resources = resolver.getResources(routesLocation);

            if (resources.length == 0) {
                log.warn("No route config files found matching {}", routesLocation);
            }

            for (Resource resource : resources) {
                String fileName = resource.getFilename();
                TransactionConfig cfg;
                try (InputStream is = resource.getInputStream()) {
                    cfg = objectMapper.readValue(is, TransactionConfig.class);
                } catch (Exception e) {
                    problems.add(fileName + ": not valid route config JSON — " + e.getMessage());
                    continue;
                }

                if (!seenIds.add(cfg.routeId())) {
                    problems.add(fileName + ": duplicate routeId '" + cfg.routeId() + "'");
                    continue;
                }

                String normalizedPath = normalizePath(cfg.path());
                String existingFile = seenPaths.putIfAbsent(normalizedPath, fileName);
                if (existingFile != null) {
                    problems.add(fileName + ": duplicate path '" + cfg.path()
                            + "' conflicts with " + existingFile);
                    continue;
                }

                List<String> errors = validator.validate(cfg, fileName);
                if (!errors.isEmpty()) {
                    problems.addAll(errors);
                    continue;
                }

                configs.add(cfg);
                log.info("Loaded route config from {}: routeId={}, path={}",
                        fileName, cfg.routeId(), cfg.path());
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to scan route configs matching " + routesLocation, e);
        }

        if (!problems.isEmpty()) {
            String report = "Route configuration is invalid (" + problems.size() + " problem(s)):\n  - "
                    + String.join("\n  - ", problems);
            if (engineProperties.getConfig().isStrict()) {
                throw new IllegalStateException(report
                        + "\nSet engine.config.strict=false to start anyway with the broken routes disabled.");
            }
            log.error("{}\nengine.config.strict=false — the routes above are DISABLED; the rest will serve.", report);
        }

        return configs;
    }

    // Normalizes trailing slashes so "/rsa/getquote" and "/rsa/getquote/" are treated as the same path
    private String normalizePath(String path) {
        if (path == null) return null;
        return path.endsWith("/") && path.length() > 1
                ? path.substring(0, path.length() - 1)
                : path;
    }
}
