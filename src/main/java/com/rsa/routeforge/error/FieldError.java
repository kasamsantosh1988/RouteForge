package com.rsa.routeforge.error;

/**
 * A single field-level problem, so partners can map an error back to the field that caused it
 * instead of parsing a joined message string.
 *
 * @param field  dot-separated context path, e.g. {@code input.regNumber}
 * @param reason short machine-readable reason, e.g. {@code REQUIRED}
 */
public record FieldError(String field, String reason) {

    public static FieldError required(String field) {
        return new FieldError(field, "REQUIRED");
    }

    /** Rendered form used in the joined summary message, preserving the legacy wording. */
    public String toMessage() {
        return "REQUIRED".equals(reason) ? field + " is required" : field + " " + reason;
    }
}
