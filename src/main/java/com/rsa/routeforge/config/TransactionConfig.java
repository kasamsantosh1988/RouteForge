package com.rsa.routeforge.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.rsa.routeforge.openapi.OpenApiValidationConfig;
import com.rsa.routeforge.soap.SoapRouteConfig;

import java.util.List;
import java.util.Set;

/**
 * One route file: an API exposed at {@code path}, implemented by {@code steps}.
 *
 * @param requestJoltSpec   optional: reshape the parsed request before it becomes {@code ctx.input}
 * @param responseJoltSpec  final shape sent back to the partner, built from the full ctx
 * @param channel           {@code rest} (default when absent: a JSON API) or {@code soap}
 * @param soap              required when {@code channel} is {@code soap}: operation, fields,
 *                          authentication and response shape. Route files with the same
 *                          {@code path} form one SOAP service
 */
public record TransactionConfig(
        String routeId,
        String path,
        String partnerCode,
        String requestJoltSpec,
        List<OrchestrationStep> steps,
        String responseJoltSpec,
        String encryptionProfile,
        String httpMethod,
        List<ConditionalJoltSpec> responseJoltSpecRules,
        OpenApiValidationConfig openApiValidation,
        String channel,
        SoapRouteConfig soap)
{
    public static final String CHANNEL_REST = "rest";
    public static final String CHANNEL_SOAP = "soap";
    public static final Set<String> CHANNELS = Set.of(CHANNEL_REST, CHANNEL_SOAP);

    @JsonCreator
    public TransactionConfig {}

    /** The pre-SOAP shape, kept so existing callers compile; the route is a REST (JSON) route. */
    public TransactionConfig(String routeId, String path, String partnerCode, String requestJoltSpec,
                             List<OrchestrationStep> steps, String responseJoltSpec, String encryptionProfile,
                             String httpMethod, List<ConditionalJoltSpec> responseJoltSpecRules,
                             OpenApiValidationConfig openApiValidation) {
        this(routeId, path, partnerCode, requestJoltSpec, steps, responseJoltSpec, encryptionProfile,
                httpMethod, responseJoltSpecRules, openApiValidation, null, null);
    }

    /** Whether this route is an operation of a SOAP service. Not a JSON property: it would clash with {@code soap}. */
    @JsonIgnore
    public boolean isSoap() {
        return CHANNEL_SOAP.equals(channel);
    }
}
