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
 * Same two guarantees as {@link RouteAuditWriter}, applied to per-call rows instead of the
 * per-request row: never fails the calling request, never silently drops a row under back-pressure.
 * Kept as a separate writer rather than folded into {@link RouteAuditWriter} because the two write
 * different tables at different cardinalities — one row per request there, zero-to-many rows per
 * request here — and reusing one class for both would tangle two different "when do we flush"
 * lifecycles together.
 */
@Component
public class StepCallAuditWriter {

    private static final Logger log = LoggerFactory.getLogger(StepCallAuditWriter.class);

    private static final String APP_INSTANCE = resolveHostname();

    private final EngineProperties props;
    private final AuditPayloadSanitizer sanitizer;
    private final EngineMetrics metrics;
    private final ObjectProvider<StepCallAuditDao> daoProvider;
    private final ExecutorService auditExecutor;
    private final ObjectProvider<StepCallAuditStore> storeProvider;
    private final AuditPayloadFileStore payloadFileStore;
    public StepCallAuditWriter(EngineProperties props,
                               AuditPayloadSanitizer sanitizer,
                               EngineMetrics metrics,
                               ObjectProvider<StepCallAuditDao> daoProvider,
                               @Qualifier("engineAuditExecutor") ExecutorService auditExecutor, ObjectProvider<StepCallAuditStore> storeProvider, AuditPayloadFileStore payloadFileStore) {
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
        if (!isEnabled()) {
            log.warn("Step-call audit is DISABLED (engine.audit.enabled=false) — no STEP_CALL_AUDIT rows will be written");
            return;
        }
        if (daoProvider.getIfAvailable() == null) {
            log.warn("Step-call audit is enabled but no StepCallAuditDao is available (no DataSource?) — auditing will be a no-op");
        }
    }

    /** Reuses the same top-level audit toggle as ROUTE_AUDIT — one switch controls both tables. */
    public boolean isEnabled() {
        return props.getAudit().isEnabled();
    }

    public void write(StepCallAuditRecord record) {
        if (record == null || !isEnabled()) {
            return;
        }
        try {
            StepCallAuditStore dao = storeProvider.getIfAvailable();
            if (dao == null) {
                return;
            }
            if (props.getAudit().isAsync()) {
                submitAsync(dao, record);
            } else {
                persist(dao, record);
            }
        } catch (Throwable t) {
            recordFailure(record, t);
        }
    }

    private void submitAsync(StepCallAuditStore dao, StepCallAuditRecord record) {
        try {
            auditExecutor.execute(MdcPropagation.wrap(() -> persist(dao, record)));
        } catch (RejectedExecutionException e) {
            log.debug("Audit executor rejected the step-call write; falling back to a synchronous insert");
            persist(dao, record);
        }
    }

    private void persist(StepCallAuditStore dao, StepCallAuditRecord record) {
        try {
            applyDefaults(record);
            sanitizePayloads(record);
            if(dao instanceof StepCallAuditDao){
                writePayloadsToFiles(record);
            }
            dao.insert(record);
            metrics.recordAuditWritten(record.getRouteId());
        } catch (Throwable t) {
            recordFailure(record, t);
        }
    }
    private void writePayloadsToFiles(StepCallAuditRecord record) {
        record.setRequestBody(tryStore(record.getPartnerCode(),"step-call", record.getStepAuditId(), "request", record.getRequestBody()));
        record.setResponseBody(tryStore(record.getPartnerCode(),"step-call", record.getStepAuditId(), "response", record.getResponseBody()));
    }
    private String tryStore(String partnerCode,String category, String id, String suffix, String content) {
        if (content == null) {
            return null;
        }
        try {
            return payloadFileStore.store(partnerCode,category, id, suffix, content);
        } catch (Exception e) {
            log.error("Failed to write {} payload file for step audit id {}", suffix, id, e);
            return null;
        }
    }
   private void applyDefaults(StepCallAuditRecord record) {
        if (record.getStepAuditId() == null) {
            record.setStepAuditId(UUID.randomUUID().toString());
        }
        if (record.getAppInstance() == null) {
            record.setAppInstance(APP_INSTANCE);
        }
        if (record.getCalledAt() == null) {
            record.setCalledAt(Instant.now());
        }
    }

    private void sanitizePayloads(StepCallAuditRecord record) {
        record.setRequestBody(sanitizer.sanitizeBody(record.getRequestBody()));
        record.setResponseBody(sanitizer.sanitizeBody(record.getResponseBody()));
    }

    private void recordFailure(StepCallAuditRecord record, Throwable t) {
        String routeId = record == null ? null : record.getRouteId();
        log.error("Step-call audit write FAILED for route '{}' step '{}' — the request itself was unaffected",
                routeId, record == null ? null : record.getStepId(), t);
        try {
            metrics.recordAuditFailure(routeId, t.getClass().getSimpleName());
        } catch (Throwable ignored) {
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