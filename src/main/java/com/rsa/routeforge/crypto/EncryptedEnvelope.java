package com.rsa.routeforge.crypto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public record EncryptedEnvelope(
        @JsonProperty("encrypted_data") String encryptedData,
        @JsonProperty("key_id") String keyId) {

    @JsonCreator
    public EncryptedEnvelope {}
}