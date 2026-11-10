package com.rsa.routeforge.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsa.routeforge.route.RouteForgeProperties;
import com.rsa.routeforge.soap.QNames;

import javax.xml.namespace.QName;
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
        Map<String, List<TransactionConfig>> soapByPath = new HashMap<>(); // SOAP service path -> its operations
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
                // SOAP operations of one service share a path; they are checked against each other
                // below. A REST route still owns its path outright.
                boolean soap = cfg.isSoap();
                String existingFile = soap
                        ? (soapByPath.containsKey(normalizedPath) ? null : seenPaths.get(normalizedPath))
                        : seenPaths.putIfAbsent(normalizedPath, fileName); // REST: claimed before validation, as before
                if (existingFile != null) {
                    problems.add(fileName + ": duplicate path '" + cfg.path()
                            + "' conflicts with " + existingFile);
                    continue;
                }

                if (soap && !routeForgeProperties.getSoap().isEnabled()) {
                    problems.add(fileName + ": route '" + cfg.routeId()
                            + "' has channel soap but routeforge.soap.enabled=false");
                    continue;
                }

                List<String> errors = validator.validate(cfg, fileName);
                if (!errors.isEmpty()) {
                    problems.addAll(errors);
                    continue;
                }

                if (soap) {
                    List<TransactionConfig> service = soapByPath.computeIfAbsent(normalizedPath, k -> new ArrayList<>());
                    String conflict = soapServiceConflict(service, cfg);
                    if (conflict != null) {
                        problems.add(fileName + ": " + conflict);
                        continue;
                    }
                    service.add(cfg);
                    seenPaths.putIfAbsent(normalizedPath, fileName);
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

    /**
     * Operations that share a path are one SOAP service: one partner, one WSDL, one public URL, and
     * each body element at most once (it is what selects the operation).
     *
     * @return a description of the conflict, or null when {@code cfg} fits the service
     */
    private static String soapServiceConflict(List<TransactionConfig> service, TransactionConfig cfg) {
        if (service.isEmpty()) {
            return null;
        }
        TransactionConfig first = service.getFirst();
        if (!Objects.equals(first.partnerCode(), cfg.partnerCode())) {
            return "SOAP operations on path '" + cfg.path() + "' must share partnerCode ('"
                    + first.partnerCode() + "' in route '" + first.routeId() + "')";
        }
        if (!Objects.equals(first.soap().wsdl(), cfg.soap().wsdl())) {
            return "SOAP operations on path '" + cfg.path() + "' must share soap.wsdl (route '" + first.routeId() + "')";
        }
        if (!Objects.equals(first.soap().publicUrl(), cfg.soap().publicUrl())) {
            return "SOAP operations on path '" + cfg.path() + "' must share soap.publicUrl (route '" + first.routeId() + "')";
        }
        QName operation = QNames.resolve(cfg.soap().operation(), cfg.soap().namespacesOrEmpty());
        for (TransactionConfig other : service) {
            QName existing = QNames.resolve(other.soap().operation(), other.soap().namespacesOrEmpty());
            if (existing.getNamespaceURI().equals(operation.getNamespaceURI())
                    && existing.getLocalPart().equals(operation.getLocalPart())) {
                return "SOAP operation " + operation + " on path '" + cfg.path()
                        + "' is already handled by route '" + other.routeId() + "'";
            }
        }
        return null;
    }

    // Normalizes trailing slashes so "/rsa/getquote" and "/rsa/getquote/" are treated as the same path
    private String normalizePath(String path) {
        if (path == null) return null;
        return path.endsWith("/") && path.length() > 1
                ? path.substring(0, path.length() - 1)
                : path;
    }
}
