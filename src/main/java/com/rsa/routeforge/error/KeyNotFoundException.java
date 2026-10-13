package com.rsa.routeforge.error;



public class KeyNotFoundException extends OrchestrationException {
    public KeyNotFoundException(String keyId) {
        super("KEY_NOT_FOUND", "No secret key registered for key_id: " + keyId, 400);
    }
}
