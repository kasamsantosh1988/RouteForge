package com.rsa.routeforge.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * Route and step metrics.
 * <p>
 * These record the <em>logical</em> status, which matters because the wire status is 200 for every
 * outcome by default — without this, error rates are invisible to monitoring.
 * <p>
 * The registry is optional so the engine still runs in tests or slim deployments without actuator.
 */
@Component
public class EngineMetrics {

    private final MeterRegistry registry;

    public EngineMetrics(ObjectProvider<MeterRegistry> registryProvider) {
        this.registry = registryProvider.getIfAvailable();
    }

    public void recordSuccess(String routeId, long startMillis) {
        if (registry == null) return;
        Timer.builder("engine.route.duration")
                .tag("route", nullSafe(routeId))
                .tag("outcome", "success")
                .tag("code", "NONE")
                .register(registry)
                .record(System.currentTimeMillis() - startMillis, TimeUnit.MILLISECONDS);
    }

    public void recordFailure(String routeId, String errorCode, int logicalStatus, long startMillis) {
        if (registry == null) return;
        Timer.builder("engine.route.duration")
                .tag("route", nullSafe(routeId))
                .tag("outcome", "failure")
                .tag("code", nullSafe(errorCode))
                .register(registry)
                .record(System.currentTimeMillis() - startMillis, TimeUnit.MILLISECONDS);

        registry.counter("engine.route.errors",
                "route", nullSafe(routeId),
                "code", nullSafe(errorCode),
                "status", String.valueOf(logicalStatus)).increment();
    }

    /**
     * A {@code ROUTE_AUDIT} row was persisted.
     * <p>
     * Paired with {@link #recordAuditFailure}, this is the only signal that the audit trail is
     * intact: audit failures are deliberately swallowed so they cannot fail a request, which means
     * without a metric a silently-dead audit trail looks exactly like a healthy one.
     */
    public void recordAuditWritten(String routeId) {
        if (registry == null) return;
        registry.counter("engine.audit.written", "route", nullSafe(routeId)).increment();
    }

    /**
     * A {@code ROUTE_AUDIT} row could not be persisted. {@code reason} must stay low-cardinality
     * (an exception simple name), never a message — see the cardinality note in the architecture doc.
     */
    public void recordAuditFailure(String routeId, String reason) {
        if (registry == null) return;
        registry.counter("engine.audit.failures",
                "route", nullSafe(routeId),
                "reason", nullSafe(reason)).increment();
    }

    public void recordStep(String routeId, String stepId, String type, boolean ok, long startMillis) {
        if (registry == null) return;
        Timer.builder("engine.step.duration")
                .tag("route", nullSafe(routeId))
                .tag("step", nullSafe(stepId))
                .tag("type", nullSafe(type))
                .tag("outcome", ok ? "success" : "failure")
                .register(registry)
                .record(System.currentTimeMillis() - startMillis, TimeUnit.MILLISECONDS);
    }

    private static String nullSafe(String value) {
        return value == null ? "unknown" : value;
    }
}
