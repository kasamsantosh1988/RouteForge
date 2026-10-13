package com.rsa.routeforge.validation;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.regex.Pattern;

@Component("regexValidator")
public class RegexValidator implements FieldValidator {

        @Override
        public boolean isValid(Object fieldValue, Map<String, Object> params,Map<String, Object> ctx) {
            if (params == null || !params.containsKey("pattern")) {
                throw new IllegalArgumentException("regexValidator requires a 'pattern' param in route config");
            }

            if (fieldValue == null) {
                return true;
            }

            String value = String.valueOf(fieldValue);

            if (value.isBlank()) {
                return true;
            }

            String pattern = String.valueOf(params.get("pattern"));
            return Pattern.matches(pattern, value);
        }
    }