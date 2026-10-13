package com.rsa.routeforge.crypto;

public interface KeyProvider {
    String name();                  // e.g. "bimasugam-static-keys"
    byte[] getKey(String keyId);
    String getActiveKeyId();
    String getPartnerActiveKeyId(); // NEW

}