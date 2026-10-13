package com.rsa.routeforge.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.PatternMatchUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * Tunables for the orchestration engine. Every default here preserves the behaviour the engine had
 * before hardening, except where the previous behaviour was unbounded (timeouts) or unsafe (payload
 * logging).
 */
@Component
@ConfigurationProperties(prefix = "engine")
public class EngineProperties {

    private final Http http = new Http();
    private final ConfigValidation config = new ConfigValidation();
    private final Logging logging = new Logging();
    private final Parallel parallel = new Parallel();
    private final Downstream downstream = new Downstream();
    private final Audit audit = new Audit();

    public Http getHttp() { return http; }
    public ConfigValidation getConfig() { return config; }
    public Logging getLogging() { return logging; }
    public Parallel getParallel() { return parallel; }
    public Downstream getDownstream() { return downstream; }
    public Audit getAudit() { return audit; }

    /**
     * Whether this route must report HTTP 200 on the wire regardless of the logical outcome.
     * <p>
     * With {@code propagate-status=false} (the default, matching historical behaviour) every route
     * answers 200 and the real status lives in the {@code status_code} body field. Turning
     * propagation on flips that for all routes except those matching {@code always-200-routes},
     * which exist for partner contracts that mandate a 200.
     */
    public boolean isAlways200(String routeId) {
        if (!http.isPropagateStatus()) {
            return true;
        }
        String[] patterns = http.getAlways200Routes().toArray(String[]::new);
        return routeId != null && PatternMatchUtils.simpleMatch(patterns, routeId);
    }

    public static class Http {
        /** Time to establish a TCP connection. */
        private int connectTimeoutMs = 2000;
        /** Time to wait for the response after the request is sent. */
        private int responseTimeoutMs = 10000;
        /** Time to wait for a connection from the pool. */
        private int poolTimeoutMs = 2000;
        /** Socket read timeout. */
        private int socketTimeoutMs = 10000;
        /** false = every route answers 200 (historical behaviour). true = real status on the wire. */
        private boolean propagateStatus = false;
        /** Route-id patterns ({@code *} wildcards) forced to 200 when propagateStatus is true. */
        private List<String> always200Routes = new ArrayList<>(List.of("bimasugam-*"));

        public int getConnectTimeoutMs() { return connectTimeoutMs; }
        public void setConnectTimeoutMs(int v) { this.connectTimeoutMs = v; }
        public int getResponseTimeoutMs() { return responseTimeoutMs; }
        public void setResponseTimeoutMs(int v) { this.responseTimeoutMs = v; }
        public int getPoolTimeoutMs() { return poolTimeoutMs; }
        public void setPoolTimeoutMs(int v) { this.poolTimeoutMs = v; }
        public int getSocketTimeoutMs() { return socketTimeoutMs; }
        public void setSocketTimeoutMs(int v) { this.socketTimeoutMs = v; }
        public boolean isPropagateStatus() { return propagateStatus; }
        public void setPropagateStatus(boolean v) { this.propagateStatus = v; }
        public List<String> getAlways200Routes() { return always200Routes; }
        public void setAlways200Routes(List<String> v) { this.always200Routes = v; }
    }

    public static class ConfigValidation {
        /** true = a config error fails startup. false = log loudly and skip the broken route. */
        private boolean strict = true;

        public boolean isStrict() { return strict; }
        public void setStrict(boolean v) { this.strict = v; }
    }

    public static class Logging {
        /**
         * Log full request/response/context payloads at INFO. Off by default: these contain
         * customer PII (names, registration numbers, quotes). When off, payloads are still
         * available at DEBUG.
         */
        private boolean payloads = false;

        public boolean isPayloads() { return payloads; }
        public void setPayloads(boolean v) { this.payloads = v; }
    }

    public static class Parallel {
        private int poolSize = 16;
        private int queueCapacity = 128;
        private int timeoutMs = 30000;
        /** true = two branches writing the same ctx key is an error rather than last-writer-wins. */
        private boolean failOnKeyCollision = true;

        public int getPoolSize() { return poolSize; }
        public void setPoolSize(int v) { this.poolSize = v; }
        public int getQueueCapacity() { return queueCapacity; }
        public void setQueueCapacity(int v) { this.queueCapacity = v; }
        public int getTimeoutMs() { return timeoutMs; }
        public void setTimeoutMs(int v) { this.timeoutMs = v; }
        public boolean isFailOnKeyCollision() { return failOnKeyCollision; }
        public void setFailOnKeyCollision(boolean v) { this.failOnKeyCollision = v; }
    }

    public static class Downstream {
        /** Require call responses to parse as JSON rather than storing raw text in the context. */
        private boolean expectJson = true;
        private int maxLogBodyChars = 500;

        public boolean isExpectJson() { return expectJson; }
        public void setExpectJson(boolean v) { this.expectJson = v; }
        public int getMaxLogBodyChars() { return maxLogBodyChars; }
        public void setMaxLogBodyChars(int v) { this.maxLogBodyChars = v; }
    }

    /**
     * Durable per-request audit: one {@code ROUTE_AUDIT} row per inbound request, holding the
     * request as received and the response as returned.
     * <p>
     * Unlike payload <em>logging</em>, this is on by default — the point of an audit trail is that
     * it exists. The PII controls are {@link #storePayloads} and {@link #maskFields} rather than an
     * all-or-nothing switch.
     */
    public static class Audit {
        /** Master switch. false = the engine builds no record and touches no datasource. */
        private boolean enabled = true;
        /**
         * true = the insert happens on {@code engine-audit-N} so the database stays off the
         * request-latency path. Under saturation it degrades to running on the request thread
         * (caller-runs), never to dropping rows.
         */
        private boolean async = true;
        /** false = audit the metadata (route, status, timing) but store null for both bodies. */
        private boolean storePayloads = true;
        /** false = store null for REQUEST_HEADERS. */
        private boolean storeHeaders = true;
        /** Per-payload ceiling. Longer bodies are stored truncated with a marker. */
        private int maxPayloadChars = 8000;
        /**
         * JSON field names replaced with {@code ***} before storage, at any nesting depth.
         * Matched case-insensitively. Empty (the default) stores payloads verbatim.
         */
        private List<String> maskFields = new ArrayList<>();
        private int poolSize = 4;
        private int queueCapacity = 500;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean v) { this.enabled = v; }
        public boolean isAsync() { return async; }
        public void setAsync(boolean v) { this.async = v; }
        public boolean isStorePayloads() { return storePayloads; }
        public void setStorePayloads(boolean v) { this.storePayloads = v; }
        public boolean isStoreHeaders() { return storeHeaders; }
        public void setStoreHeaders(boolean v) { this.storeHeaders = v; }
        public int getMaxPayloadChars() { return maxPayloadChars; }
        public void setMaxPayloadChars(int v) { this.maxPayloadChars = v; }
        public List<String> getMaskFields() { return maskFields; }
        public void setMaskFields(List<String> v) { this.maskFields = v; }
        public int getPoolSize() { return poolSize; }
        public void setPoolSize(int v) { this.poolSize = v; }
        public int getQueueCapacity() { return queueCapacity; }
        public void setQueueCapacity(int v) { this.queueCapacity = v; }
    }
}
