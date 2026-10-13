package com.rsa.routeforge.validation;

import org.springframework.stereotype.Component;

import java.util.Map;

@Component("minValueValidator")
public class MinValueValidator implements FieldValidator {

    @Override
    public boolean isValid(Object fieldValue, Map<String, Object> params,Map<String, Object> ctx) {
        if (fieldValue == null) {
            return true;
        }

        if (!(fieldValue instanceof Number number)) {
            return false;
        }

        double min = ((Number) params.get("min")).doubleValue();
        return number.doubleValue() >= min;
    }
}