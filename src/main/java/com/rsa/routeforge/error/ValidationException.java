package com.rsa.routeforge.error;

import java.util.List;

/**
 * Raised when request validation fails.
 * <p>
 * The joined {@code "; "} summary message is preserved verbatim for backwards compatibility with
 * existing partners; the structured {@link #details()} are additive.
 */
public class ValidationException extends OrchestrationException {

    public ValidationException(List<String> errorCodes,List<String> errorMsgs) {
        super(String.join("; ", errorCodes), String.join("; ", errorMsgs), 400);
    }
    /**
     * Legacy entry point — a flat list of pre-rendered messages.
     * Kept so callers outside the engine continue to compile.
     */
    public ValidationException(List<String> errors) {
        super(ErrorCode.VALIDATION_FAILED,
                String.join("; ", errors),
                String.join("; ", errors),
                errors.stream().map(e -> new FieldError(e, "INVALID")).toList());
    }

    private ValidationException(String joined, List<FieldError> details) {
        super(ErrorCode.VALIDATION_FAILED, joined, joined, details);
    }

    /**
     * Preferred entry point. Renders the same joined message the legacy path produces, while
     * retaining the per-field structure.
     * <p>
     * A distinct factory rather than a constructor overload: {@code List<String>} and
     * {@code List<FieldError>} erase to the same signature.
     */
    public static ValidationException of(List<FieldError> details) {
        String joined = details.stream().map(FieldError::toMessage).reduce((a, b) -> a + "; " + b).orElse("");
        return new ValidationException(joined, details);
    }
}
