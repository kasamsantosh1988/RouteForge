package com.rsa.routeforge.crypto;

import java.time.OffsetDateTime;
import java.util.List;

public interface SecretStoreClient {
    byte[] fetchSecret(String keyId);
    String fetchSecretValue(String keyId);
    String getActiveKeyId();
    String getPartnerActiveKeyId();// throws KeyNotFoundException if keyId doesn't exist
    // NEW — needed for rotation
    void storeSecret(String keyId, byte[] secretValue);
    void setActiveKeyId(String keyId);
    void markDeprecated(String keyId, OffsetDateTime deprecatedOn);
    List<String> listDeprecatedKeyIds(OffsetDateTime olderThan);
    void deleteSecret(String keyId);
}