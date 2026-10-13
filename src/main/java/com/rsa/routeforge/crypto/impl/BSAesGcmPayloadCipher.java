package com.rsa.routeforge.crypto.impl;


import com.rsa.routeforge.crypto.EncryptedEnvelope;
import com.rsa.routeforge.crypto.KeyProvider;
import com.rsa.routeforge.crypto.KeyProviderRegistry;
import com.rsa.routeforge.crypto.PayloadCipher;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

@Component
public class BSAesGcmPayloadCipher implements PayloadCipher {

    private static final String KEY_PROVIDER_NAME = "bimasugam-runtime-keys";
    private final KeyProviderRegistry keyProviderRegistry;

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int IV_LEN = 12;
    private static final int TAG_LEN_BITS = 128;
    // Constructed once, reused across every encrypt() call — thread-safe, avoids repeated re-seeding overhead
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    public BSAesGcmPayloadCipher(KeyProviderRegistry keyProviderRegistry) {
        this.keyProviderRegistry = keyProviderRegistry;
    }


    @Override
    public String name() {
        return "bimasugam-aes-gcm";
    }

    @Override
    public EncryptedEnvelope encrypt(String plaintext) {
        KeyProvider provider = getKeyProvider();
        return encryptInternal(plaintext,provider.getActiveKeyId(),provider);
    }

    @Override
    public EncryptedEnvelope encryptByPartnerKey(String plaintext) {
        KeyProvider provider = getKeyProvider();
        return encryptInternal(plaintext,provider.getPartnerActiveKeyId(),provider);
    }

    private KeyProvider getKeyProvider() {
        return keyProviderRegistry.resolve(KEY_PROVIDER_NAME);
    }

    private byte[] generateIv() {
        byte[] iv = new byte[IV_LEN];
        SECURE_RANDOM.nextBytes(iv);
        return iv;
    }

    private byte[] combine(byte[] iv, byte[] ciphertextAndTag) {
        byte[] combined = new byte[iv.length + ciphertextAndTag.length];

        System.arraycopy(iv, 0, combined, 0, iv.length);
        System.arraycopy(
                ciphertextAndTag,
                0,
                combined,
                iv.length,
                ciphertextAndTag.length
        );

        return combined;
    }

    private EncryptedEnvelope encryptInternal(String plaintext, String keyId, KeyProvider keyProvider) {
        try {
            byte[] key = keyProvider.getKey(keyId);
            byte[] iv = generateIv();

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_LEN_BITS, iv));
            byte[] ciphertextAndTag = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            byte[] combined = combine(iv, ciphertextAndTag);

            return new EncryptedEnvelope(Base64.getEncoder().encodeToString(combined), keyId);
        } catch (Exception e) {
            throw new RuntimeException("Encryption failed for keyId=" + keyId, e);
        }
    }

    @Override
    public String decrypt(EncryptedEnvelope envelope) {
        try {
            KeyProvider keyProvider = getKeyProvider();
            byte[] key = keyProvider.getKey(envelope.keyId());

            byte[] combined = Base64.getDecoder().decode(envelope.encryptedData());

            byte[] iv = new byte[IV_LEN];
            System.arraycopy(combined, 0, iv, 0, IV_LEN);
            byte[] ciphertextAndTag = new byte[combined.length - IV_LEN];
            System.arraycopy(combined, IV_LEN, ciphertextAndTag, 0, ciphertextAndTag.length);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_LEN_BITS, iv));
            byte[] plaintext = cipher.doFinal(ciphertextAndTag);

            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("Decryption failed for keyId=" + envelope.keyId(), e);
        }
    }
    @Override
    public String decryptByPartnerKey(EncryptedEnvelope envelope) {
        return decrypt(envelope);
    }

    @Override
    public String decrypt(String data,String keyId) {
        try {
            KeyProvider keyProvider = getKeyProvider();
            byte[] key = keyProvider.getKey(keyId);

            byte[] combined = Base64.getDecoder().decode(data);

            byte[] iv = new byte[IV_LEN];
            System.arraycopy(combined, 0, iv, 0, IV_LEN);
            byte[] ciphertextAndTag = new byte[combined.length - IV_LEN];
            System.arraycopy(combined, IV_LEN, ciphertextAndTag, 0, ciphertextAndTag.length);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_LEN_BITS, iv));
            byte[] plaintext = cipher.doFinal(ciphertextAndTag);

            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("Decryption failed for keyId=" + keyId, e);
        }
    }
}