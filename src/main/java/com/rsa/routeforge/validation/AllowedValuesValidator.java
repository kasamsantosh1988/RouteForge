package com.rsa.routeforge.validation;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component("allowedValuesValidator")
public class AllowedValuesValidator implements FieldValidator {

        @Override
        @SuppressWarnings("unchecked")
        public boolean isValid(Object fieldValue, Map<String, Object> params, Map<String, Object> ctx) {
            if (params == null || !params.containsKey("allowedValues")) {
                throw new IllegalArgumentException("allowedValuesValidator requires an 'allowedValues' param in route config");
            }

            if (fieldValue == null) {
                return true;
            }

            String value = String.valueOf(fieldValue);
            if (value.isBlank()) {
                return true;
            }

            List<Object> allowedValues = (List<Object>) params.get("allowedValues");

            return allowedValues.stream()
                    .map(String::valueOf)
                    .anyMatch(v -> v.equalsIgnoreCase(value));
        }
    }