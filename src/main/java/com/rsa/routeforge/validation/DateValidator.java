package com.rsa.routeforge.validation;

import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Map;

@Component("dateValidator")
public class DateValidator implements FieldValidator {

    @Override
    public boolean isValid(Object fieldValue, Map<String, Object> params, Map<String, Object> ctx) {
        if (params == null || !params.containsKey("format")) {
            throw new IllegalArgumentException("dateValidator requires a 'format' param in route config");
        }

        if (fieldValue == null) {
            return true;
        }

        String fieldValueStr = String.valueOf(fieldValue).trim();
        if (fieldValueStr.isBlank() || "null".equalsIgnoreCase(fieldValueStr)) {
            return true;
        }

        String format = String.valueOf(params.get("format"));

        DateTimeFormatter formatter;
        try {
            formatter = DateTimeFormatter.ofPattern(format);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("dateValidator has an invalid 'format' param: " + format, e);
        }

        LocalDate value;
        try {
            value = LocalDate.parse(fieldValueStr, formatter);
        } catch (DateTimeParseException e) {
            return false;
        }

        LocalDate minDate = resolveBound(params.get("minDate"), formatter);
        if (minDate != null && value.isBefore(minDate)) {
            return false;
        }

        LocalDate maxDate = resolveBound(params.get("maxDate"), formatter);
        return maxDate == null || !value.isAfter(maxDate);
    }

    // Resolves a bound that's either the literal keyword "today" or a date string in the same format.
    private LocalDate resolveBound(Object rawBound, DateTimeFormatter formatter) {
        if (rawBound == null) return null;
        String bound = String.valueOf(rawBound);
        if ("currentDate".equalsIgnoreCase(bound)) {
            return LocalDate.now();
        }
        try {
            return LocalDate.parse(bound, formatter);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(
                    "dateValidator has an invalid min/maxDate value '" + bound + "' for format '" + formatter + "'", e);
        }
    }
}