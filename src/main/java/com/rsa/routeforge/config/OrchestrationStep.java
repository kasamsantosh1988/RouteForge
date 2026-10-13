package com.rsa.routeforge.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.rsa.routeforge.auth.AuthConfig;
import com.rsa.routeforge.validation.ValidationRule;

import java.util.List;
import java.util.Map;

public record OrchestrationStep(
        String id,
        String type,
        String uri,
        String method,
        String requestJoltSpec,
        String responseJoltSpec,
        String responseVariable,
        Boolean useFullContext,
        String beanRef,
        String condition,
        List<OrchestrationStep> thenSteps,
        List<OrchestrationStep> elseSteps,
        List<OrchestrationStep> branches, List<String> requiredFields,
        ErrorCheckConfig errorCheck,
        List<ValidationRule> fieldValidations,
        Map<String,String> extraHeaders,
        AuthConfig auth,
        String encryptionProfile
       ) {
    @JsonCreator
    public OrchestrationStep {}

    public static OrchestrationStep call(String id, String uri, String method,
                                         String requestJoltSpec, String responseJoltSpec,
                                         String responseVariable, Boolean useFullContext) {
        return new OrchestrationStep(id, "call", uri, method, requestJoltSpec, responseJoltSpec,
                responseVariable, useFullContext, null,null, null, null, null, null,null,null, null,null,null);
    }

    public static OrchestrationStep businessLogic(String id, String beanRef) {
        return new OrchestrationStep(id, "businessLogic", null, null, null, null,
                null, null, beanRef, null, null,null, null, null,null,null, null,null,null);
    }

    public static OrchestrationStep conditional(String id, String condition,
                                                List<OrchestrationStep> thenSteps,
                                                List<OrchestrationStep> elseSteps) {
        return new OrchestrationStep(id, "conditional", null, null, null, null,
                null, null, null, condition, thenSteps, elseSteps, null,null,null,null, null,null,null);
    }

    public static OrchestrationStep parallel(String id, List<OrchestrationStep> branches) {
        return new OrchestrationStep(id, "parallel", null, null, null, null,
                null, null, null, null, null, null, branches,null,null,null, null,null,null);
    }
}