package com.rsa.routeforge.inbound;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsa.routeforge.config.EngineMetrics;
import com.rsa.routeforge.config.EngineProperties;
import com.rsa.routeforge.config.TransactionConfig;
import com.rsa.routeforge.crypto.CipherRegistry;
import com.rsa.routeforge.error.ErrorEnvelopeFactory;
import com.rsa.routeforge.route.RouteForgeProperties;
import com.rsa.routeforge.soap.SoapCodec;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * Spring wiring: the codec beans are injected in {@code @Order} order, so SOAP routes reach
 * {@link SoapCodec} and every other route stays on {@link JsonCodec}.
 */
class InboundCodecWiringTest {

    @Test
    void springInjectsCodecsInOrder() {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            ObjectMapper mapper = new ObjectMapper();
            ctx.registerBean(ObjectMapper.class, () -> mapper);
            ctx.registerBean(EngineProperties.class);
            ctx.registerBean(RouteForgeProperties.class);
            ctx.registerBean(ErrorEnvelopeFactory.class, () -> new ErrorEnvelopeFactory(mapper));
            ctx.registerBean(CipherRegistry.class, () -> new CipherRegistry(List.of()));
            ctx.registerBean(EngineMetrics.class);
            // Registered JSON first, to prove the order comes from @Order and not registration order.
            ctx.register(JsonCodec.class, SoapCodec.class, InboundCodecRegistry.class);
            ctx.refresh();

            InboundCodecRegistry registry = ctx.getBean(InboundCodecRegistry.class);
            TransactionConfig rest = new TransactionConfig("bimasugam-quote", "/q", null, null, null, null, null, "POST", null, null);
            TransactionConfig soap = new TransactionConfig("mibl-proposal", "/ws/mibl", "MIBL", null, null, null, null,
                    "POST", null, null, TransactionConfig.CHANNEL_SOAP, null);

            assertInstanceOf(JsonCodec.class, registry.resolve(rest));
            assertInstanceOf(SoapCodec.class, registry.resolve(soap));
        }
    }
}
