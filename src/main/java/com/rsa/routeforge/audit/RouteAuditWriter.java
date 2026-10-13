package com.rsa.routeforge.audit;

import com.rsa.routeforge.config.EngineMetrics;
import com.rsa.routeforge.config.EngineProperties;
import com.rsa.routeforge.config.MdcPropagation;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

/**
 * The engine's only door into the audit subsystem.
 * <p>
 * Two guarantees, and every design choice here follows from them:
 * <ol>
 *   <li><strong>It cannot fail a request.</strong> {@link #write} catches {@code Throwable} and
 *       returns normally. The engine calls it from a {@code finally} block after the response
 *       envelope is already decided, so a database outage must not turn a working route into a 500.
 *       Failures surface as an ERROR log line and the {@code engine.audit.failures} metric.</li>
 *   <li><strong>It does not silently lose rows.</strong> The executor uses {@code CallerRunsPolicy}
 *       and rejection falls back to a synchronous write, so back-pressure shows up as latency
 *       rather than as gaps in the trail.</li>
 * </ol>
 * The DAO is injected as an {@link ObjectProvider} — the same optional-dependency pattern
 * {@code EngineMetrics} uses for {@code MeterRegistry} — so the engine still starts in a slim
 * deployment or a unit test with no datasource on the classpath.
 */
@Component
public class RouteAuditWriter {

    private static final Logger log = LoggerFactory.getLogger(RouteAuditWriter.class);

    /** Resolved once: the lookup can block, and it cannot change while the JVM is up. */
    private static final String APP_INSTANCE = resolveHostname();

    private final EngineProperties props;
    private final AuditPayloadSanitizer sanitizer;
    private final EngineMetrics metrics;
    private final ObjectProvider<RouteAuditDao> daoProvider;
    private final ExecutorService auditExecutor;
    private final ObjectProvider<RouteAuditStore> storeProvider;
    private final AuditPayloadFileStore payloadFileStore;

    public RouteAuditWriter(EngineProperties props,
                            AuditPayloadSanitizer sanitizer,
                            EngineMetrics metrics,
                            ObjectProvider<RouteAuditDao> daoProvider,
                            @Qualifier("engineAuditExecutor") ExecutorService auditExecutor, ObjectProvider<RouteAuditStore> storeProvider, AuditPayloadFileStore payloadFileStore) {
        this.props = props;
        this.sanitizer = sanitizer;
        this.metrics = metrics;
        this.daoProvider = daoProvider;
        this.auditExecutor = auditExecutor;
        this.storeProvider = storeProvider;
        this.payloadFileStore = payloadFileStore;
    }

    @PostConstruct
    void reportConfiguration() {
        EngineProperties.Audit cfg = props.getAudit();
        if (!cfg.isEnabled()) {
            log.warn("Route audit is DISABLED (engine.audit.enabled=false) — no ROUTE_AUDIT rows will be written");
            return;
        }
        if (daoProvider.getIfAvailable() == null) {
            log.warn("Route audit is enabled but no RouteAuditDao is available (no DataSource?) — "
                    + "auditing will be a no-op");
            return;
        }
        log.info("Route audit enabled: async={} storePayloads={} maxPayloadChars={} maskFields={}",
                cfg.isAsync(), cfg.isStorePayloads(), cfg.getMaxPayloadChars(), cfg.getMaskFields());
    }

    /**
     * Whether the engine should bother building a {@link RouteAuditRecord} at all. Checked before
     * construction so that with auditing off the request path allocates nothing.
     */
    public boolean isEnabled() {
        return props.getAudit().isEnabled();
    }

    /**
     * Persists one audit row. Never throws.
     * <p>
     * The caller hands over the record and must not touch it afterwards — with
     * {@code engine.audit.async=true} it is read on another thread.
     */
    public void write(RouteAuditRecord record) {
        if (record == null || !isEnabled()) {
            return;
        }
        try {
            RouteAuditStore dao = storeProvider.getIfAvailable();
            if (dao == null) {
                return;   // already reported at startup; stay quiet on the request path
            }
            if (props.getAudit().isAsync()) {
                submitAsync(dao, record);
            } else {
                persist(dao, record);
            }
        } catch (Throwable t) {
            // Belt-and-braces: submitAsync and persist each handle their own failures, so reaching
            // here means something unforeseen. It still must not escape into the engine's finally.
            recordFailure(record, t);
        }
    }

    private void submitAsync(RouteAuditStore dao, RouteAuditRecord record) {
        try {
            auditExecutor.execute(MdcPropagation.wrap(() -> persist(dao, record)));
        } catch (RejectedExecutionException e) {
            // Reached when the pool is shutting down; CallerRunsPolicy covers ordinary saturation.
            // Writing inline is better than losing the row during a drain.
            log.debug("Audit executor rejected the write; falling back to a synchronous insert");
            persist(dao, record);
        }
    }

    private void persist(RouteAuditStore dao, RouteAuditRecord record) {
        try {
            applyDefaults(record);
            sanitizePayloads(record);
            if(dao instanceof RouteAuditDao){
                writePayloadsToFiles(record);
            }
            dao.insert(record);
            metrics.recordAuditWritten(record.getRouteId());
        } catch (Throwable t) {
            recordFailure(record, t);
        }
    }
    private void writePayloadsToFiles(RouteAuditRecord record) {
        record.setRequestBody(tryStore(record.getPartnerCode(),"route", record.getAuditId(), "request", record.getRequestBody()));
        record.setResponseBody(tryStore(record.getPartnerCode(),"route", record.getAuditId(), "response", record.getResponseBody()));
    }
    private String tryStore(String partnerCode,String category, String id, String suffix, String content) {
        if (content == null) {
            return null;
        }
        try {
            return payloadFileStore.store(partnerCode,category, id, suffix, content);
        } catch (Exception e) {
            log.error("Failed to write {} payload file for audit id {}", suffix, id, e);
            return null;
        }
    }
    /**
     * Sanitizes and writes each payload to its own file, then replaces the in-memory body with the
     * file path — that path is what actually lands in ROUTE_AUDIT.REQUEST_BODY/RESPONSE_BODY.
     * <p>
     * Each payload fails independently: if the response file can't be written, the request path is
     * still stored and the row still gets inserted. Losing a payload reference is preferable to
     * losing the whole audit row over a single file-write failure.
     */
    private void applyDefaults(RouteAuditRecord record) {
        if (record.getAuditId() == null) {
            // Generated here rather than by the database so the same code works on Oracle
            // (SEQUENCE), PostgreSQL (IDENTITY) and SQL Server without dialect-specific key retrieval.
            record.setAuditId(UUID.randomUUID().toString());
        }
        if (record.getAppInstance() == null) {
            record.setAppInstance(APP_INSTANCE);
        }
        if (record.getCompletedAt() == null) {
            record.setCompletedAt(Instant.now());
        }
    }

    private void sanitizePayloads(RouteAuditRecord record) {
        record.setRequestBody(sanitizer.sanitizeBody(record.getRequestBody()));
        record.setResponseBody(sanitizer.sanitizeBody(record.getResponseBody()));
        // Headers are serialized and sanitized by the engine at capture time; nothing to redo here.
    }

    private void recordFailure(RouteAuditRecord record, Throwable t) {
        String routeId = record == null ? null : record.getRouteId();
        log.error("Audit write FAILED for route '{}' — the request itself was unaffected", routeId, t);
        try {
            metrics.recordAuditFailure(routeId, t.getClass().getSimpleName());
        } catch (Throwable ignored) {
            // A metrics registry failure must not mask the audit failure we just logged.
        }
    }

    private static String resolveHostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "unknown";
        }
    }
}
