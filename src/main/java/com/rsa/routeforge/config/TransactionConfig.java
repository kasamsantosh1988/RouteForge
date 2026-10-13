package com.rsa.routeforge.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.rsa.routeforge.openapi.OpenApiValidationConfig;

import java.util.List;

public record TransactionConfig(
        String routeId,
        String path,
        String partnerCode,
        String requestJoltSpec,   // optional: reshape raw incoming payload before it becomes ctx.input
        List<OrchestrationStep> steps,
        String responseJoltSpec,
        String encryptionProfile,
        String httpMethod,
        List<ConditionalJoltSpec> responseJoltSpecRules,
        OpenApiValidationConfig openApiValidation)  // final shape sent back to the partner, built from the full ctx
{
    @JsonCreator
    public TransactionConfig {}
}
