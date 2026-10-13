package com.rsa.routeforge.crypto.impl;


import com.rsa.routeforge.crypto.KeyProvider;
import com.rsa.routeforge.error.KeyNotFoundException;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Map;

@Component
public class BSStaticKeyProvider implements KeyProvider {

    private final Map<String, byte[]> keys = Map.of(
            "key_v1_2025", "12345678912345678912345678912345".getBytes(StandardCharsets.UTF_8)
    );
    @Override
    public String name() {
        return "bimasugam-static-keys";
    }

    @Override
    public byte[] getKey(String keyId) {
        byte[] key = keys.get(keyId);
        if (key == null) {
            throw new KeyNotFoundException(keyId);
        }
        return key;
    }

    @Override
    public String getActiveKeyId() {
        return "key_v1_2025";
    }

    @Override
    public String getPartnerActiveKeyId() {
        return "";
    }
}