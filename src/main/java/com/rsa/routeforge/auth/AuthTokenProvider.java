package com.rsa.routeforge.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsa.routeforge.crypto.SecretStoreClient;
import com.rsa.routeforge.error.ErrorCode;
import com.rsa.routeforge.error.ExceptionTranslator;
import com.rsa.routeforge.error.OrchestrationException;
import io.micrometer.common.util.StringUtils;
import lombok.RequiredArgsConstructor;
import org.apache.camel.Exchange;
import org.apache.camel.ProducerTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class AuthTokenProvider {

    private static final Logger log = LoggerFactory.getLogger(AuthTokenProvider.class);
    private static final Pattern SECRET_PATTERN = Pattern.compile("\\$\\{([^}]+)}");
    private final SecretStoreClient secretStoreClient;
    private final ProducerTemplate producerTemplate;
    private final ObjectMapper objectMapper;
    private final ExceptionTranslator translator;

    private final Map<String, CachedToken> cache = new ConcurrentHashMap<>();
    @Autowired
    private Environment env;
    /**
     * Returns a valid token for this auth config, fetching a fresh one only if the cached entry
     * is missing or within its expiry buffer.
     * <p>
     * compute() is atomic per key, so concurrent callers for the same cache key serialize on the
     * fetch rather than each firing their own token request. A still-valid entry short-circuits
     * without ever entering fetchToken.
     */
    public String getToken(AuthConfig auth) {
        String cacheKey = cacheKeyFor(auth);
        log.info("check cacheKey  Avilability {}",cacheKey);
        CachedToken token = cache.compute(cacheKey, (key, existing) ->
                (existing != null && !existing.isNearExpiry(auth.expiryBufferSeconds()))
                        ? existing
                        : fetchToken(auth));

        log.info("token returned {}",token);
        return token.value();
    }

    /** Call after a downstream 401 using this auth config — forces a real refetch on the next getToken. */
    public void invalidate(AuthConfig auth) {
        cache.remove(cacheKeyFor(auth));
    }

    private String cacheKeyFor(AuthConfig auth) {
        return auth.name() != null ? auth.name() : auth.tokenUri() + "|" + auth.secretKeyId();
    }

    private CachedToken fetchToken(AuthConfig auth) {
        Map<String, String> params = new LinkedHashMap<>(
                auth.staticParams() != null ? auth.staticParams() : Map.of());
        log.info("resolving keys before getToken Call Initiating ");
        params = params.entrySet()
                .stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> {
                            String value = entry.getValue();
                            Matcher matcher = SECRET_PATTERN.matcher(value);

                            if (matcher.matches()) {
                                return secretStoreClient.fetchSecretValue(matcher.group(1));
                            }

                            return value;
                        }
                ));
        if (auth.secretKeyId() != null || auth.secretKeyIdPointer()!=null) {
            if (auth.secretParamName() == null) {
                throw new OrchestrationException(ErrorCode.CONFIG_ERROR,
                        "Auth config '" + cacheKeyFor(auth) + "' sets secretKeyId but not secretParamName");
            }
            String secretKeyId=auth.secretKeyId();
            if(StringUtils.isBlank(secretKeyId))
             secretKeyId = secretStoreClient.fetchSecretValue(auth.secretKeyIdPointer());

            String secretBytes = secretStoreClient.fetchSecretValue(secretKeyId);
            params.put(auth.secretParamName(), secretBytes);
        }

        String method = auth.method() != null ? auth.method() : "POST";
        boolean isJson = "json".equalsIgnoreCase(auth.bodyType());
        String body = isJson ? toJsonSafely(params) : toFormEncoded(params);

        log.info("Fetching auth token for '{}' from {}", cacheKeyFor(auth), auth.tokenUri());
        String resolvedTokenUri = auth.tokenUri() != null
                ? env.resolvePlaceholders(auth.tokenUri())
                : null;
        Exchange result;
        try {
            result = producerTemplate.request(
                    resolvedTokenUri + "?bridgeEndpoint=true&throwExceptionOnFailure=false",
                    ex -> {
                        ex.getIn().setBody(body);
                        ex.getIn().setHeader(Exchange.HTTP_METHOD, method);
                        ex.getIn().setHeader(Exchange.CONTENT_TYPE,
                                isJson ? "application/json" : "application/x-www-form-urlencoded");
                    });
        } catch (Exception e) {
            throw translator.translateDownstream(e, "auth:" + cacheKeyFor(auth));
        }

        if (result.getException() != null) {
            throw translator.translateDownstream(result.getException(), "auth:" + cacheKeyFor(auth));
        }

        Integer status = result.getMessage().getHeader(Exchange.HTTP_RESPONSE_CODE, Integer.class);
        String raw = result.getMessage().getBody(String.class);

        // Body intentionally omitted from the exception message — token endpoints often echo
        // request params, including the secret, back in error responses.
        if (status != null && status >= 400) {
            throw new OrchestrationException(ErrorCode.DOWNSTREAM_INVALID_RESPONSE,
                    "Auth token fetch for '" + cacheKeyFor(auth) + "' failed with status " + status);
        }

        Map<String, Object> parsed;
        try {
            parsed = objectMapper.readValue(raw, Map.class);
        } catch (Exception e) {
            throw new OrchestrationException(ErrorCode.DOWNSTREAM_INVALID_RESPONSE,
                    "Auth token response for '" + cacheKeyFor(auth) + "' is not JSON", e);
        }

        Object tokenValue = parsed.get(auth.tokenField());
        if (tokenValue == null) {
            throw new OrchestrationException(ErrorCode.CONFIG_ERROR,
                    "Auth field '" + auth.tokenField() + "' missing from token response for '" + cacheKeyFor(auth) + "'");
        }

        long expiresIn = 3600;
        if (auth.expiresInField() != null && parsed.get(auth.expiresInField()) != null) {
            try {
                expiresIn = Long.parseLong(String.valueOf(parsed.get(auth.expiresInField())));
            } catch (NumberFormatException e) {
                log.warn("Auth '{}': expiresInField '{}' was not numeric, defaulting to 3600s",
                        cacheKeyFor(auth), auth.expiresInField());
            }
        }

        log.info("Fetched auth token for '{}', expires in {}s", cacheKeyFor(auth), expiresIn);
        return new CachedToken(String.valueOf(tokenValue), Instant.now().plusSeconds(expiresIn));
    }

    private String toFormEncoded(Map<String, String> params) {
        return params.entrySet().stream()
                .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "="
                        + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
    }

    private String toJsonSafely(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            throw new OrchestrationException(ErrorCode.SERIALIZATION_FAILED,
                    "Failed to serialize auth request: " + e.getMessage(), e);
        }
    }

    private record CachedToken(String value, Instant expiresAt) {
        boolean isNearExpiry(long bufferSeconds) {
            return Instant.now().isAfter(expiresAt.minusSeconds(bufferSeconds));
        }
    }
}
