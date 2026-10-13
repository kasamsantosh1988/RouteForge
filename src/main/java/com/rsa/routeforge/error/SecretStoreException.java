package com.rsa.routeforge.error;


public class SecretStoreException extends OrchestrationException {
    public SecretStoreException(String message, Throwable cause) {
        super("SECRET_STORE_ERROR", message, 503);
        if (cause != null) initCause(cause);
    }
}

