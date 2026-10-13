package com.rsa.routeforge.config;


import com.fasterxml.jackson.annotation.JsonCreator;

public record ErrorCheckConfig(
        String condition,       // SpEL expression evaluated against the raw response, e.g. "['status'] == 'FAILED'"
        String codeField,       // dot-path into the response for the error code, e.g. "errorCode"
        String messageField,    // dot-path into the response for the error message, e.g. "errorMessage"
        String defaultCode,     // fallback if codeField is missing/unresolvable
        Integer httpStatus) {   // status_code to report, e.g. 400

    @JsonCreator
    public ErrorCheckConfig {}
}

