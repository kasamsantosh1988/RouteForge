package com.rsa.routeforge.config;

import org.slf4j.MDC;

import java.util.Map;
import java.util.function.Supplier;

/**
 * Carries the calling thread's MDC into a worker thread.
 * <p>
 * Without this, parallel branches log without the {@code traceId} of the request that spawned them,
 * which is exactly when correlation matters most.
 */
public final class MdcPropagation {

    private MdcPropagation() {
    }

    public static <T> Supplier<T> wrap(Supplier<T> delegate) {
        Map<String, String> captured = MDC.getCopyOfContextMap();
        return () -> {
            Map<String, String> previous = MDC.getCopyOfContextMap();
            if (captured != null) {
                MDC.setContextMap(captured);
            } else {
                MDC.clear();
            }
            try {
                return delegate.get();
            } finally {
                if (previous != null) {
                    MDC.setContextMap(previous);
                } else {
                    MDC.clear();
                }
            }
        };
    }

    /**
     * {@code Runnable} form, for work submitted to an {@code ExecutorService} that produces no
     * value — audit writes, whose failures are logged rather than returned and so are unfindable
     * without the {@code traceId} of the request that produced them.
     */
    public static Runnable wrap(Runnable delegate) {
        Supplier<Void> wrapped = wrap(() -> {
            delegate.run();
            return null;
        });
        return wrapped::get;
    }
}
