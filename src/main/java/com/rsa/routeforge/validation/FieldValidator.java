package com.rsa.routeforge.validation;

import java.util.Map;

public interface FieldValidator {
    boolean isValid(Object fieldValue, Map<String, Object> params, Map<String, Object> ctx);
}
