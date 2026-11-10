package com.rsa.routeforge.soap;

import com.fasterxml.jackson.annotation.JsonCreator;

import java.util.Set;

/**
 * How a SOAP operation authenticates its caller.
 *
 * @param type     {@code bodyCredentials}: user and password are fields of the request, checked by
 *                 the host's {@link CredentialVerifier}. {@code gateway}: APIM or the load balancer
 *                 has already authenticated the caller; optionally require {@code header}.
 *                 {@code none}: no check
 * @param user     for {@code bodyCredentials}: the {@code fields} entry holding the user name
 * @param password for {@code bodyCredentials}: the {@code fields} entry holding the password; it is
 *                 removed from {@code ctx.input} once checked
 * @param header   for {@code gateway}: a request header that must be present and non-blank
 */
public record SoapAuthConfig(String type, String user, String password, String header) {

    public static final String BODY_CREDENTIALS = "bodyCredentials";
    public static final String GATEWAY = "gateway";
    public static final String NONE = "none";
    public static final Set<String> TYPES = Set.of(BODY_CREDENTIALS, GATEWAY, NONE);

    @JsonCreator
    public SoapAuthConfig {
    }
}
