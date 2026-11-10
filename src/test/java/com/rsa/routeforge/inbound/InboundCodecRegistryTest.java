package com.rsa.routeforge.inbound;

import com.rsa.routeforge.audit.RouteAuditRecord;
import com.rsa.routeforge.config.TransactionConfig;
import com.rsa.routeforge.error.OrchestrationException;
import org.apache.camel.Exchange;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

class InboundCodecRegistryTest {

    private static final TransactionConfig SOAP_ROUTE = route("mibl-proposal");
    private static final TransactionConfig JSON_ROUTE = route("bimasugam-quote");

    @Test
    void firstCodecThatSupportsTheRouteWins() {
        FakeCodec soap = new FakeCodec(cfg -> cfg.routeId().startsWith("mibl"));
        FakeCodec json = new FakeCodec(cfg -> true);
        InboundCodecRegistry registry = new InboundCodecRegistry(List.of(soap, json));

        assertSame(soap, registry.resolve(SOAP_ROUTE));
        assertSame(json, registry.resolve(JSON_ROUTE));
    }

    @Test
    void routeNoCodecClaimsFallsBackToTheLastCodec() {
        FakeCodec first = new FakeCodec(cfg -> false);
        FakeCodec last = new FakeCodec(cfg -> false);
        InboundCodecRegistry registry = new InboundCodecRegistry(List.of(first, last));

        assertSame(last, registry.resolve(JSON_ROUTE));
    }

    @Test
    void codecThatThrowsInSupportsIsSkippedNotPropagated() {
        FakeCodec broken = new FakeCodec(cfg -> { throw new IllegalStateException("boom"); });
        FakeCodec json = new FakeCodec(cfg -> true);
        InboundCodecRegistry registry = new InboundCodecRegistry(List.of(broken, json));

        assertSame(json, registry.resolve(JSON_ROUTE));
    }

    @Test
    void requiresAtLeastOneCodec() {
        assertThrows(IllegalArgumentException.class, () -> new InboundCodecRegistry(List.of()));
    }

    private static TransactionConfig route(String routeId) {
        return new TransactionConfig(routeId, "/" + routeId, null, null, null, null, null, "POST", null, null);
    }

    private record FakeCodec(Predicate<TransactionConfig> claims) implements InboundCodec {
        @Override public boolean supports(TransactionConfig cfg) { return claims.test(cfg); }
        @Override public Map<String, Object> parseRequest(TransactionConfig cfg, Exchange exchange, String rawBody) { return Map.of(); }
        @Override public void writeSuccess(Exchange exchange, TransactionConfig cfg, Object body, String uuid, String traceId, RouteAuditRecord audit) { }
        @Override public void writeError(Exchange exchange, TransactionConfig cfg, OrchestrationException error, String traceId, String failedStep, String uuid, RouteAuditRecord audit) { }
    }
}
