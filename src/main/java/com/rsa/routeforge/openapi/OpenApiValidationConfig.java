package com.rsa.routeforge.openapi;

public record OpenApiValidationConfig(
        String specLocation,
        String operationPath,
        String httpMethod
) {
}