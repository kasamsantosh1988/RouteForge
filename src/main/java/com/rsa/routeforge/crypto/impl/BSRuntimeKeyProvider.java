package com.rsa.routeforge.crypto.impl;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.rsa.routeforge.crypto.KeyProvider;
import com.rsa.routeforge.crypto.SecretStoreClient;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class BSRuntimeKeyProvider implements KeyProvider {

    // The actual secret source (Azure Key Vault, DB, etc.) — injected, not hardcoded here
    private final SecretStoreClient secretStoreClient;

    // In-memory cache of already-fetched secrets, keyed by key_id.
    // expireAfterWrite: how long a fetched secret stays cached before the next lookup re-fetches it —
    //   balances "don't hit the secret store on every request" against "pick up rotation reasonably soon."
    // maximumSize: caps how many distinct key_ids can be cached at once, evicting least-recently-used
    //   beyond that — protects memory if key_id ever became attacker-influenced or you end up with
    //   many historical keys in rotation.
    private final Cache<String, byte[]> cache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(5))
            .maximumSize(100)
            .build();
    private final Cache<String, String> activeKeyIdCache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(1))   // shorter than the key cache — rotation should propagate fast
            .maximumSize(1)
            .build();

    private final Cache<String, String> activePartnerKeyIdCache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(1))   // shorter than the key cache — rotation should propagate fast
            .maximumSize(1)
            .build();

    public BSRuntimeKeyProvider(SecretStoreClient secretStoreClient) {
        this.secretStoreClient = secretStoreClient;
    }

    @Override
    public String name() {
        return "bimasugam-runtime-keys";
    }

    @Override
    public byte[] getKey(String keyId) {
        byte[] cached = cache.getIfPresent(keyId);
        if (cached != null) {
            return cached;
        }
        byte[] fetched = secretStoreClient.fetchSecret(keyId); // throws KeyNotFoundException naturally if missing
        cache.put(keyId, fetched);
        return fetched;
    }

    @Override
    public String getActiveKeyId() {
        String cached = activeKeyIdCache.getIfPresent("active");
        if (cached != null) {
            return cached;
        }
        String active = secretStoreClient.getActiveKeyId();
        activeKeyIdCache.put("active", active);
        return active;
    }
    @Override
    public String getPartnerActiveKeyId() {
        String cached = activePartnerKeyIdCache.getIfPresent("active");
        if (cached != null) {
            return cached;
        }
        String active = secretStoreClient.getPartnerActiveKeyId();
        activePartnerKeyIdCache.put("active", active);
        return active;
    }
}