package com.rsa.routeforge.validation;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.YearMonth;
import java.util.Map;

@Component("manufacturingMonthValidator")
public class ManufacturingMonthValidator implements FieldValidator {

    private static final Clock CLOCK = Clock.systemDefaultZone();

    @Override
    public boolean isValid(Object fieldValue, Map<String, Object> params,Map<String, Object> ctx) {
        if (fieldValue == null) {
            return true;
        }

        try {
            YearMonth value = YearMonth.parse(String.valueOf(fieldValue));
            return !value.isAfter(YearMonth.now(CLOCK));

        } catch (Exception _) {
            return false;
        }
    }
}