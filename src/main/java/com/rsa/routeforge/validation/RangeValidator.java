package com.rsa.routeforge.validation;

import org.springframework.stereotype.Component;

import java.util.Map;

@Component("rangeValidator")
public class RangeValidator implements FieldValidator {

    @Override
    public boolean isValid(Object fieldValue, Map<String, Object> params,Map<String, Object> ctx) {
        if (fieldValue == null) {
            return true;
        }

        if (!(fieldValue instanceof Number number)) {
            return false;
        }

        int min = ((Number) params.get("min")).intValue();
        int max = ((Number) params.get("max")).intValue();
        int value = number.intValue();
        return value >= min && value <= max;
    }
}