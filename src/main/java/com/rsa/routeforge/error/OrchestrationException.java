package com.rsa.routeforge.error;

import java.util.List;

/**
 * The engine's single business exception.
 * <p>
 * Unchecked so it propagates cleanly through the recursive step execution (nested conditionals,
 * parallel branches) without every level declaring {@code throws}.
 * <p>
 * <strong>Invariant: {@link #getMessage()} is always safe to return to a partner.</strong> It is
 * either a static {@link ErrorCode#clientMessage()} or, for the legacy string-code constructors, a
 * message the caller explicitly chose to expose. Anything derived from an underlying exception —
 * hostnames, ports, stack detail, downstream response bodies — belongs in {@link #internalDetail()},
 * which is logged and never returned.
 */
public class OrchestrationException extends RuntimeException {

    private final String code;
    private final ErrorCode errorCode;      // null when built from a legacy string code
    private final int httpStatus;
    private final String internalDetail;
    private final List<FieldError> details;

    public OrchestrationException(ErrorCode errorCode) {
        this(errorCode, null, null);
    }

    public OrchestrationException(ErrorCode errorCode, String internalDetail) {
        this(errorCode, internalDetail, null);
    }

    public OrchestrationException(ErrorCode errorCode, String internalDetail, Throwable cause) {
        this(errorCode, errorCode.defaultHttpStatus(), internalDetail, List.of(), cause);
    }

    public OrchestrationException(ErrorCode errorCode, int httpStatus, String internalDetail,
                                  List<FieldError> details, Throwable cause) {
        super(errorCode.clientMessage(), cause);
        this.code = errorCode.code();
        this.errorCode = errorCode;
        this.httpStatus = httpStatus;
        this.internalDetail = internalDetail;
        this.details = details == null ? List.of() : List.copyOf(details);
    }

    public OrchestrationException(String code, String message) {
        this(code, message, 400);
    }

    public OrchestrationException(String code, String message, int httpStatus) {
        super(message);
        this.code = code;
        this.errorCode = null;
        this.httpStatus = httpStatus;
        this.internalDetail = message;
        this.details = List.of();
    }

    /** Protected hook for subclasses that need a bespoke client-facing message (e.g. validation). */
    protected OrchestrationException(ErrorCode errorCode, String clientMessage, String internalDetail,
                                     List<FieldError> details) {
        super(clientMessage);
        this.code = errorCode.code();
        this.errorCode = errorCode;
        this.httpStatus = errorCode.defaultHttpStatus();
        this.internalDetail = internalDetail;
        this.details = details == null ? List.of() : List.copyOf(details);
    }

    public String getCode() {
        return code;
    }

    /** Null when this was built from a legacy string code. */
    public ErrorCode errorCode() {
        return errorCode;
    }

    public int getHttpStatus() {
        return httpStatus;
    }

    /** Log-only detail. Never place this in a response. */
    public String internalDetail() {
        return internalDetail;
    }

    public List<FieldError> details() {
        return details;
    }

}
