package com.rsa.routeforge.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Dedicated pools for {@code parallel} step branches and for audit writes.
 * <p>
 * Previously branches ran on the common {@link java.util.concurrent.ForkJoinPool}. That is doubly
 * wrong here: Camel's Spring-Boot platform-http consumer already dispatches request handling via
 * {@code CompletableFuture.runAsync()}, so the route thread <em>is</em> a common-pool worker
 * submitting more blocking work to the same pool and then blocking on {@code join()}. Blocking I/O
 * on the common pool also starves every other parallel stream in the JVM.
 */
@Configuration
public class EngineExecutorConfig {

    @Bean(name = "engineParallelExecutor", destroyMethod = "shutdown")
    public ExecutorService engineParallelExecutor(EngineProperties props) {
        EngineProperties.Parallel cfg = props.getParallel();
        return boundedPool("engine-parallel-", cfg.getPoolSize(), cfg.getQueueCapacity());
    }

    /**
     * Pool for {@code ROUTE_AUDIT} inserts, separate from the parallel pool so a slow database
     * cannot consume the threads that execute {@code parallel} branches.
     * <p>
     * {@code CallerRunsPolicy} again, and here the choice is load-bearing: when the queue fills, the
     * insert runs on the request thread. That trades latency for completeness, which is the right
     * way round for an audit trail — a discard policy would silently lose exactly the rows produced
     * during the incident you would later want to investigate.
     */
    @Bean(name = "engineAuditExecutor", destroyMethod = "shutdown")
    public ExecutorService engineAuditExecutor(EngineProperties props) {
        EngineProperties.Audit cfg = props.getAudit();
        return boundedPool("engine-audit-", cfg.getPoolSize(), cfg.getQueueCapacity());
    }

    private static ExecutorService boundedPool(String threadPrefix, int poolSize, int queueCapacity) {
        AtomicInteger counter = new AtomicInteger();

        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                poolSize,
                poolSize,
                60L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                runnable -> {
                    Thread t = new Thread(runnable, threadPrefix + counter.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                },
                // Run on the submitting thread rather than dropping work when saturated. Slows the
                // caller down, which is the back-pressure we want.
                new ThreadPoolExecutor.CallerRunsPolicy());
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }
}
