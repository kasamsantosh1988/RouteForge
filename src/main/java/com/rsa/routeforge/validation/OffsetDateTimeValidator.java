package com.rsa.routeforge.validation;

import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.Map;

@Component("offsetDateTimeValidator")
public class OffsetDateTimeValidator implements FieldValidator {

    @Override
    public boolean isValid(Object fieldValue, Map<String, Object> params,Map<String, Object> ctx) {
        if (fieldValue == null) {
            return true;
        }

        String value = String.valueOf(fieldValue);
        if (value.isBlank()) {
            return true;
        }

        try {
            OffsetDateTime.parse(value);
            return true;
        } catch (Exception _) {
            return false;
        }
    }
}