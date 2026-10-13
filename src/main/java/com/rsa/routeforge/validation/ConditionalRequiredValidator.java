package com.rsa.routeforge.validation;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component("conditionalRequiredValidator")
public class ConditionalRequiredValidator implements FieldValidator {

    @Override
    public boolean isValid(Object fieldValue, Map<String, Object> params, Map<String, Object> ctx) {
        if (params == null || params.get("dependsOnField") == null || params.get("dependsOnValue") == null) {
            throw new IllegalArgumentException(
                    "conditionalRequiredValidator requires 'dependsOnField' and 'dependsOnValue' params");
        }

        String dependsOnField = String.valueOf(params.get("dependsOnField"));
        Object dependsOnValue = params.get("dependsOnValue");
        Object actualValue = resolvePath(ctx, dependsOnField);

        boolean conditionMet = dependsOnValue instanceof List<?> list
                ? list.stream().anyMatch(v -> String.valueOf(v).equals(String.valueOf(actualValue)))
                : String.valueOf(dependsOnValue).equals(String.valueOf(actualValue));

        if (!conditionMet) {
            return true; // condition not met — field isn't required in this case, so it's valid regardless
        }
        return fieldValue != null && !String.valueOf(fieldValue).isBlank();
    }

    private Object resolvePath(Map<String, Object> ctx, String path) {
        Object current = ctx;
        for (String part : path.split("\\.")) {
            if (!(current instanceof Map)) return null;
            current = ((Map<?, ?>) current).get(part);
        }
        return current;
    }

}