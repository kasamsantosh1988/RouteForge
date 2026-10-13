package com.rsa.routeforge.validation;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.regex.Pattern;

@Component("phoneNumberValidator")
public class PhoneNumberValidator implements FieldValidator {
    private static final Pattern INDIAN_MOBILE = Pattern.compile("^[6-9]\\d{9}$");

    @Override
    public boolean isValid(Object fieldValue, Map<String, Object> params,Map<String, Object> ctx) {
        return INDIAN_MOBILE.matcher(String.valueOf(fieldValue)).matches();
    }
}
