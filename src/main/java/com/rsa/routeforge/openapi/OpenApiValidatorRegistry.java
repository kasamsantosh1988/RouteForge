package com.rsa.routeforge.openapi;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.rsa.routeforge.error.ErrorCode;
import com.rsa.routeforge.error.OrchestrationException;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class OpenApiValidatorRegistry {

    private final Map<String,OpenApiInteractionValidator> cache = new ConcurrentHashMap<>();
    private final ResourceLoader resourceLoader;

    public OpenApiValidatorRegistry(ResourceLoader resourceLoader) {
        this.resourceLoader = resourceLoader;
    }

    public OpenApiInteractionValidator get(String specLocation) {
        return cache.computeIfAbsent(
                specLocation,
                this::build);
    }

    private OpenApiInteractionValidator build1(
            String specLocation) {
        try {
            ClassPathResource resource = new ClassPathResource(specLocation);
            String specContent = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return OpenApiInteractionValidator.createForInlineApiSpecification(specContent).build();
        } catch (Exception ex) {
            throw new OrchestrationException(
                    ErrorCode.CONFIG_ERROR,
                    "Failed to load OpenAPI spec: "
                            + specLocation,
                    ex
            );
        }
    }

    private OpenApiInteractionValidator build(String specLocation) {
        try {
            Resource resource = resourceLoader.getResource(specLocation);

            if (!resource.exists()) {
                throw new OrchestrationException(
                        ErrorCode.CONFIG_ERROR,
                        "OpenAPI spec not found: " + specLocation
                );
            }

            String specContent = new String(
                            resource.getInputStream().readAllBytes(),
                            StandardCharsets.UTF_8);

            return OpenApiInteractionValidator
                    .createForInlineApiSpecification(specContent)
                    .build();

        } catch (Exception ex) {
            throw new OrchestrationException(
                    ErrorCode.CONFIG_ERROR,
                    "Failed to load OpenAPI spec: " + specLocation,
                    ex
            );
        }
    }
}
