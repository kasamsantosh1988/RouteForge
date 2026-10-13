package com.rsa.routeforge.validation;

import java.util.List;
import java.util.Map;

public record ValidationRule(
        String beanRef,             // name of a Spring bean implementing FieldValidator
        List<String> fields,        // which field paths to check, e.g. "input.mobileNumber"
        Map<String, Object> params,
        String errorCode,       // NEW — e.g. "INVALID_MOBILE_NUMBER"
        String errorMessage// extra config the bean needs, e.g. a regex pattern
) {}
