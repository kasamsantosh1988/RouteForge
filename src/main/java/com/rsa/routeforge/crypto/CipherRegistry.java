package com.rsa.routeforge.crypto;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class CipherRegistry {

    private final Map<String, PayloadCipher> ciphersByName;

    @Autowired
    public CipherRegistry(List<PayloadCipher> ciphers) {
        this.ciphersByName = ciphers.stream()
                .collect(Collectors.toMap(PayloadCipher::name, c -> c));
    }

    public PayloadCipher resolve(String profileName) {
        PayloadCipher cipher = ciphersByName.get(profileName);
        if (cipher == null) {
            throw new IllegalArgumentException("No PayloadCipher registered for profile: " + profileName);
        }
        return cipher;
    }
}