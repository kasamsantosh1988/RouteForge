package com.rsa.routeforge.crypto;

public interface PayloadCipher {
    String name();                              // e.g. "bimasugam-aes-gcm" — matched against config
    EncryptedEnvelope encrypt(String plaintext); // used for response encryption
    String decrypt(EncryptedEnvelope envelope);
    EncryptedEnvelope encryptByPartnerKey(String plaintext);
    String decryptByPartnerKey(EncryptedEnvelope envelope);
    String decrypt(String data,String keyId);
    // used for request decryption
}