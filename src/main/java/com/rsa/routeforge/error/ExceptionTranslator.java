package com.rsa.routeforge.error;

import org.apache.camel.http.base.HttpOperationFailedException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.net.ssl.SSLException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;

/**
 * Turns an arbitrary {@link Throwable} into an {@link OrchestrationException}.
 * <p>
 * This is the engine's single classification point. Camel wraps the real failure in a
 * {@code CamelExecutionException}, and {@code CompletableFuture} wraps it again in a
 * {@code CompletionException}, so everything here works by walking the cause chain rather than
 * inspecting only the outermost type.
 */
@Component
public class ExceptionTranslator {

    private final int maxBodyChars;

    public ExceptionTranslator(@Value("${engine.downstream.max-log-body-chars:500}") int maxBodyChars) {
        this.maxBodyChars = maxBodyChars;
    }

    /** General-purpose translation for the outer boundary. */
    public OrchestrationException translate(Throwable t) {
        OrchestrationException existing = findInChain(t, OrchestrationException.class);
        if (existing != null) {
            return existing;
        }
        if (findInChain(t, TimeoutException.class) != null) {
            return new OrchestrationException(ErrorCode.PARALLEL_TIMEOUT, describe(t), t);
        }
        OrchestrationException downstream = classifyDownstream(t, null);
        return downstream != null
                ? downstream
                : new OrchestrationException(ErrorCode.INTERNAL_SERVER_ERROR, describe(t), t);
    }

    /**
     * Translation for a failed {@code call} step. Falls back to {@code DOWNSTREAM_CALL_FAILED}
     * rather than {@code INTERNAL_SERVER_ERROR}, because we already know the failure came from an
     * outbound call.
     */
    public OrchestrationException translateDownstream(Throwable t, String stepId) {
        OrchestrationException existing = findInChain(t, OrchestrationException.class);
        if (existing != null) {
            return existing;
        }
        OrchestrationException classified = classifyDownstream(t, stepId);
        return classified != null
                ? classified
                : new OrchestrationException(ErrorCode.DOWNSTREAM_CALL_FAILED, detail(stepId, describe(t)), t);
    }

    /** Returns null when nothing in the chain looks like a transport or HTTP failure. */
    private OrchestrationException classifyDownstream(Throwable t, String stepId) {
        HttpOperationFailedException http = findInChain(t, HttpOperationFailedException.class);
        if (http != null) {
            return new OrchestrationException(codeForStatus(http.getStatusCode()), detail(stepId, describe(http)), t);
        }
        if (findInChain(t, UnknownHostException.class) != null) {
            return new OrchestrationException(ErrorCode.DOWNSTREAM_UNAVAILABLE, detail(stepId, describe(t)), t);
        }
        if (findInChain(t, SSLException.class) != null) {
            return new OrchestrationException(ErrorCode.DOWNSTREAM_TLS_ERROR, detail(stepId, describe(t)), t);
        }
        // Covers java.net.SocketTimeoutException and HttpClient's ConnectTimeoutException, which
        // extends InterruptedIOException rather than any shared timeout supertype.
        if (findInChain(t, SocketTimeoutException.class) != null
                || matchesInChain(t, x -> x.getClass().getSimpleName().contains("Timeout"))) {
            return new OrchestrationException(ErrorCode.DOWNSTREAM_TIMEOUT, detail(stepId, describe(t)), t);
        }
        if (findInChain(t, ConnectException.class) != null
                || findInChain(t, NoRouteToHostException.class) != null) {
            return new OrchestrationException(ErrorCode.DOWNSTREAM_UNAVAILABLE, detail(stepId, describe(t)), t);
        }
        return null;
    }

    /**
     * Builds the error for a downstream response we read ourselves.
     * <p>
     * Calls run with {@code throwExceptionOnFailure=false} so a 4xx/5xx arrives as a normal
     * response carrying its body, rather than as an exception with the body discarded. That body
     * is captured into the internal detail — it is the single most useful thing for triage — and
     * is never returned to the partner.
     */
    public OrchestrationException forStatus(int status, String stepId, String uri, String responseBody) {
        String description = "HTTP " + status + " from " + uri + " body=" + truncate(responseBody);
        return new OrchestrationException(codeForStatus(status), detail(stepId, description));
    }

    private static ErrorCode codeForStatus(int status) {
        if (status == 401 || status == 403) return ErrorCode.DOWNSTREAM_AUTH_FAILED;
        if (status == 404) return ErrorCode.DOWNSTREAM_NOT_FOUND;
        if (status == 429) return ErrorCode.DOWNSTREAM_RATE_LIMITED;
        if (status >= 500) return ErrorCode.DOWNSTREAM_ERROR;
        if (status >= 400) return ErrorCode.DOWNSTREAM_REJECTED;
        return ErrorCode.DOWNSTREAM_CALL_FAILED;
    }

    private String describe(HttpOperationFailedException e) {
        return "HTTP " + e.getStatusCode() + " " + e.getStatusText()
                + " from " + e.getUri()
                + " body=" + truncate(e.getResponseBody());
    }

    private String describe(Throwable t) {
        Throwable root = rootCause(t);
        return root.getClass().getName() + ": " + root.getMessage();
    }

    private static String detail(String stepId, String description) {
        return stepId == null ? description : "step '" + stepId + "': " + description;
    }

    private String truncate(String body) {
        if (body == null) return "<none>";
        return body.length() <= maxBodyChars
                ? body
                : body.substring(0, maxBodyChars) + "...<truncated " + (body.length() - maxBodyChars) + " chars>";
    }

    // ---- cause-chain helpers -------------------------------------------------

    /** Unwraps {@code CamelExecutionException}, {@code CompletionException}, etc. */
    public static Throwable unwrap(Throwable t) {
        Throwable current = t;
        Set<Throwable> seen = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        while (current != null && seen.add(current)) {
            boolean isWrapper = current instanceof CompletionException
                    || current instanceof ExecutionException
                    || current.getClass().getSimpleName().equals("CamelExecutionException");
            if (!isWrapper || current.getCause() == null) {
                return current;
            }
            current = current.getCause();
        }
        return t;
    }

    private static Throwable rootCause(Throwable t) {
        Throwable current = t;
        Set<Throwable> seen = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        while (current.getCause() != null && seen.add(current)) {
            current = current.getCause();
        }
        return current;
    }

    private static <T extends Throwable> T findInChain(Throwable t, Class<T> type) {
        Set<Throwable> seen = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable c = t; c != null && seen.add(c); c = c.getCause()) {
            if (type.isInstance(c)) {
                return type.cast(c);
            }
        }
        return null;
    }

    private static boolean matchesInChain(Throwable t, Predicate<Throwable> predicate) {
        Set<Throwable> seen = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable c = t; c != null && seen.add(c); c = c.getCause()) {
            if (predicate.test(c)) {
                return true;
            }
        }
        return false;
    }

    /** Exposed for diagnostics/tests. */
    public Map<String, String> describeChain(Throwable t) {
        Map<String, String> chain = new java.util.LinkedHashMap<>();
        Set<Throwable> seen = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        int i = 0;
        for (Throwable c = t; c != null && seen.add(c); c = c.getCause()) {
            chain.put(i++ + ":" + c.getClass().getSimpleName(), c.getMessage());
        }
        return chain;
    }
}
