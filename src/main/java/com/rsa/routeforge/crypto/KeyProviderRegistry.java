package com.rsa.routeforge.crypto;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class KeyProviderRegistry {

    private final Map<String, KeyProvider> providersByName;

    @Autowired
    public KeyProviderRegistry(List<KeyProvider> providers) {
        this.providersByName = providers.stream()
                .collect(Collectors.toMap(KeyProvider::name, p -> p));
    }

    public KeyProvider resolve(String providerName) {
        KeyProvider provider = providersByName.get(providerName);
        if (provider == null) {
            throw new IllegalStateException("No KeyProvider registered with name: " + providerName);
        }
        return provider;
    }
}