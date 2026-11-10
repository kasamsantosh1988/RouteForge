package com.rsa.routeforge.inbound;

import com.rsa.routeforge.config.TransactionConfig;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Picks the {@link InboundCodec} for a route.
 * <p>
 * Never throws: the engine resolves the codec before its error boundary, and needs one to write
 * even the error response. A route no codec claims falls back to the last codec in order, which is
 * {@link JsonCodec} in every standard setup.
 */
@Component
public class InboundCodecRegistry {

    private final List<InboundCodec> codecs;

    /** @param codecs every codec bean, in {@code @Order} order (Spring injects them sorted) */
    public InboundCodecRegistry(List<InboundCodec> codecs) {
        if (codecs == null || codecs.isEmpty()) {
            throw new IllegalArgumentException("At least one InboundCodec is required");
        }
        this.codecs = List.copyOf(codecs);
    }

    public InboundCodec resolve(TransactionConfig cfg) {
        for (InboundCodec codec : codecs) {
            try {
                if (codec.supports(cfg)) {
                    return codec;
                }
            } catch (RuntimeException ignored) {
                // A codec that cannot decide is treated as not claiming the route.
            }
        }
        return codecs.getLast();
    }
}
