package com.rsa.routeforge.soap;

/**
 * Checks a partner's login for SOAP operations configured with {@code bodyCredentials}.
 * <p>
 * Implemented by the host application (PIE), which owns where partner credentials are kept. A
 * SOAP route that needs it fails with a configuration error when no implementation is present.
 * Implementations should compare secrets in constant time (for example
 * {@link java.security.MessageDigest#isEqual}) and must not log the password.
 */
public interface CredentialVerifier {

    /**
     * @param partnerCode the route's {@code partnerCode}
     * @param userName    as sent by the partner; may be null
     * @param password    as sent by the partner; may be null
     * @return true when the credentials are valid for this partner
     */
    boolean verify(String partnerCode, String userName, String password);
}
