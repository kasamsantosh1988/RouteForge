package com.rsa.routeforge.validation;

public record ValidationResult(boolean valid, String errorMessage) {
    public static ValidationResult ok() { return new ValidationResult(true, null); }
    public static ValidationResult fail(String msg) { return new ValidationResult(false, msg); }
}
