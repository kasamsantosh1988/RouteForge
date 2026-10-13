package com.rsa.routeforge.crypto.impl;

import com.bettercloud.vault.Vault;
import com.bettercloud.vault.VaultConfig;
import com.bettercloud.vault.VaultException;
import com.bettercloud.vault.response.AuthResponse;
import com.bettercloud.vault.response.LogicalResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsa.routeforge.crypto.SecretStoreClient;
import com.rsa.routeforge.error.KeyNotFoundException;
import com.rsa.routeforge.error.SecretStoreException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.*;
import java.util.function.Supplier;

@Slf4j
@Component
public class HashiCorpKeyVaultSecretStoreBSClient implements SecretStoreClient {

    private static final String ACTIVE_RSKEY_POINTER = "RSTOBS-active-key-id";
    private static final String ACTIVE_BSKEY_POINTER = "BSTORS-active-key-id";

    private final String vaultUri;
    private final String roleId;
    private final String secretId;
    private final String path;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // NOT final anymore — needs to be replaceable when the token expires and we re-authenticate
    private volatile Vault authenticatedVault;

    public HashiCorpKeyVaultSecretStoreBSClient(
            @Value("${hashicorp.vault.uri}") String vaultUri,
            @Value("${hashicorp.vault.roleid}") String roleId,
            @Value("${hashicorp.vault.secretid}") String secretId,
            @Value("${hashicorp.vault.bs.path}") String path) {
        this.vaultUri = vaultUri;
        this.roleId = roleId;
        this.secretId = secretId;
        this.path = path;
        synchronized(this) {
            this.authenticatedVault = authenticate();
        }
    }

    // ------------------------------------------------------------------
    // Authentication — extracted so it can be re-run on token expiry
    // ------------------------------------------------------------------

    private Vault authenticate() {
        try {
            VaultConfig config = new VaultConfig().address(vaultUri).build();
            Vault vault = new Vault(config);

            AuthResponse auth = vault.auth().loginByAppRole(roleId, secretId);
            String token = auth.getAuthClientToken();

            VaultConfig authConfig = new VaultConfig().address(vaultUri).token(token).build();
            return new Vault(authConfig);
        } catch (VaultException e) {
            throw new SecretStoreException("Failed to authenticate with Vault: " + e.getMessage(), e);
        }
    }

    // Wraps every Vault call: retries ONCE after re-authenticating if the token looks expired/rejected.
    // Vault returns HTTP 403 for both "bad token" and "expired token" — re-auth-and-retry covers both.
    private <T> T withVault(Supplier<T> vaultCall) {
        try {
            return vaultCall.get();
        } catch (RuntimeException e) {
            if (isAuthFailure(e)) {
                log.warn("Vault call failed with an auth-related error, re-authenticating and retrying once: {}", e.getMessage());
                this.authenticatedVault = authenticate();
                return vaultCall.get(); // let a second failure propagate genuinely
            }
            throw e;
        }
    }

    private boolean isAuthFailure(RuntimeException e) {
        Throwable cause = e.getCause() != null ? e.getCause() : e;
        String message = String.valueOf(cause.getMessage());
        return (message.contains("403") || message.contains("permission denied")
                ||  message.contains("invalid token") || message.contains("expired token"));
    }

    // ------------------------------------------------------------------
    // Reads
    // ------------------------------------------------------------------

    @Override
    public byte[] fetchSecret(String keyId) {
        try {
            Map<String, Object> allFields = readAllFields();
            Object rawValue = allFields.get(keyId);
            if (rawValue == null) {
                throw new KeyNotFoundException(keyId);
            }
            KeyEntry entry = parseKeyEntry(rawValue);
            return Base64.getDecoder().decode(entry.secret);
        } catch (KeyNotFoundException knf) {
            throw knf; // genuine "key doesn't exist" — keep as-is, 400
        } catch (Exception e) {
            // anything else (Vault unreachable, malformed data, etc.) is a SERVICE problem, not a client one
            throw new SecretStoreException("Failed to fetch secret for key_id '" + keyId + "': " + e.getMessage(), e);
        }
    }


    @Override
    public String fetchSecretValue(String keyId) {
        try {
            Map<String, Object> allFields = readAllFields();
            Object rawValue = allFields.get(keyId);
            if (rawValue == null) {
                throw new KeyNotFoundException(keyId);
            }
            return rawValue.toString();
        } catch (KeyNotFoundException knf) {
            throw knf; // genuine "key doesn't exist" — keep as-is, 400
        } catch (Exception e) {
            // anything else (Vault unreachable, malformed data, etc.) is a SERVICE problem, not a client one
            throw new SecretStoreException("Failed to fetch secret for key_id '" + keyId + "': " + e.getMessage(), e);
        }
    }
    private KeyEntry parseKeyEntry(Object rawValue) {
        try {
            if (rawValue instanceof String json) {
                return objectMapper.readValue(json, KeyEntry.class);
            }

            return objectMapper.convertValue(rawValue, KeyEntry.class);
        } catch (Exception e) {
            throw new SecretStoreException(
                    "Failed to parse key entry",
                    e
            );
        }
    }
    @Override
    public String getActiveKeyId() {
        return getActiveKeyId(ACTIVE_RSKEY_POINTER);
    }

    @Override
    public String getPartnerActiveKeyId() {
        return getActiveKeyId(ACTIVE_BSKEY_POINTER);
    }

    private String getActiveKeyId(String pointer) {
        try {
            Map<String, Object> allFields = readAllFields();
            Object active = allFields.get(pointer);
            if (active == null) {
                String message = "No active key_id configured under: " + pointer;
                log.error(message);
                throw new SecretStoreException(message, null);
            }
            return String.valueOf(active);
        } catch (SecretStoreException sse) {
            throw sse;
        } catch (Exception e) {
            log.error("Failed to read active key_id from pointer={}", pointer, e);
            throw new SecretStoreException("Failed to read active key_id from: " + pointer, e);
        }
    }


    // ------------------------------------------------------------------
    // Writes
    // ------------------------------------------------------------------

    @Override
    public void storeSecret(String keyId, byte[] secretValue) {
        try {
            Map<String, Object> allFields = readAllFields();

            KeyEntry entry = new KeyEntry();
            entry.secret = Base64.getEncoder().encodeToString(secretValue); // FIXED — was byte[], now a proper String
            entry.status = "active";
            entry.deprecatedOn = null;

            allFields.put(keyId, objectMapper.writeValueAsString(entry));
            writeAllFields(allFields);

            log.info("Secret '{}' stored successfully", keyId);
        } catch (Exception e) {
            throw new SecretStoreException("Failed to store secret for key_id '" + keyId + "': " + e.getMessage(), e);
        }
    }

    @Override
    public void setActiveKeyId(String keyId) {
        try {
            Map<String, Object> allFields = readAllFields();
            allFields.put(ACTIVE_RSKEY_POINTER, keyId);
            writeAllFields(allFields);
            log.info("Active key_id set to '{}'", keyId);
        } catch (Exception e) {
            throw new SecretStoreException("Failed to set active key_id to '" + keyId + "': " + e.getMessage(), e);
        }
    }

    @Override
    public void markDeprecated(String keyId, OffsetDateTime deprecatedOn) {
        try {
            Map<String, Object> allFields = readAllFields();
            Object rawValue = allFields.get(keyId);
            if (rawValue == null) {
                throw new KeyNotFoundException(keyId);
            }
            KeyEntry entry = objectMapper.convertValue(rawValue, KeyEntry.class);
            entry.status = "deprecated";
            entry.deprecatedOn = deprecatedOn.toString();

            allFields.put(keyId, objectMapper.writeValueAsString(entry));
            writeAllFields(allFields);

            log.info("Key '{}' marked deprecated as of {}", keyId, deprecatedOn);
        } catch (KeyNotFoundException knf) {
            throw knf;
        } catch (Exception e) {
            throw new SecretStoreException("Failed to mark key '" + keyId + "' deprecated: " + e.getMessage(), e);
        }
    }

    @Override
    public List<String> listDeprecatedKeyIds(OffsetDateTime olderThan) {
        try {
            Map<String, Object> allFields = readAllFields();
            List<String> result = new ArrayList<>();

            for (Map.Entry<String, Object> field : allFields.entrySet()) {
                if (field.getKey().equals(ACTIVE_RSKEY_POINTER)) continue;

                try {
                    KeyEntry entry = objectMapper.convertValue(field.getValue(), KeyEntry.class);
                    if (!"deprecated".equals(entry.status) || entry.deprecatedOn == null) continue;

                    OffsetDateTime deprecatedOn = OffsetDateTime.parse(entry.deprecatedOn);
                    if (deprecatedOn.isBefore(olderThan)) {
                        result.add(field.getKey());
                    }
                } catch (Exception parseError) {
                    log.warn("Skipping malformed key entry '{}' during deprecation scan: {}", field.getKey(), parseError.getMessage());
                }
            }
            return result; // NEVER null — fixes the scheduler NPE risk directly
        } catch (Exception e) {
            throw new SecretStoreException("Failed to list deprecated keys: " + e.getMessage(), e);
        }
    }

    @Override
    public void deleteSecret(String keyId) {
        try {
            Map<String, Object> allFields = readAllFields();
            if (allFields.remove(keyId) == null) {
                throw new KeyNotFoundException(keyId);
            }
            writeAllFields(allFields);
            log.info("Secret '{}' deleted successfully", keyId);
        } catch (KeyNotFoundException knf) {
            throw knf;
        } catch (Exception e) {
            throw new SecretStoreException("Failed to delete secret for key_id '" + keyId + "': " + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------
    // Vault read/write helpers — every field lives under one KV path
    // ------------------------------------------------------------------

    private Map<String, Object> readAllFields() {
        return withVault(() -> {
            try {
                LogicalResponse response = authenticatedVault.logical().read(path);
                if (response == null || response.getData() == null) {
                    return new LinkedHashMap<>();
                }
                return new LinkedHashMap<>(response.getData());
            } catch (VaultException e) {
                throw new SecretStoreException("Failed to read from Vault path '" + path + "': " + e.getMessage(), e);
            }
        });
    }

    private void writeAllFields(Map<String, Object> allFields) {
        withVault(() -> {
            try {
                LogicalResponse writeResponse = authenticatedVault.logical().write(path, allFields);
                int statusCode = writeResponse.getRestResponse().getStatus();
                if (statusCode < 200 || statusCode >= 300) {
                    throw new SecretStoreException("Vault write to '" + path + "' returned HTTP " + statusCode, null);
                }
                return null;
            } catch (VaultException e) {
                throw new SecretStoreException("Failed to write to Vault path '" + path + "': " + e.getMessage(), e);
            }
        });
    }

    // Small internal shape for per-key metadata stored as JSON in each field's value
    private static class KeyEntry {
        public String secret;
        public String status;
        public String deprecatedOn;
    }
}
