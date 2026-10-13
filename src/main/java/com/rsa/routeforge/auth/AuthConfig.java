package com.rsa.routeforge.auth;

import java.util.Map;

public record AuthConfig(
        String name,                 // identifies this auth setup, used as the cache key
        String tokenUri,             // the token endpoint to call
        String method,               // usually "POST"
        Map<String, String> staticParams,  // grant_type, client_id, scope — plain values
        String secretParamName,      // which param needs the secret, e.g. "client_secret"
        String secretKeyId,          // Vault key_id — passed to your existing SecretStoreClient
        String bodyType,             // "form" or "json"
        String tokenField,           // where the token sits in the response, e.g. "access_token"
        String expiresInField,       // where the TTL sits in the response, e.g. "expires_in"
        long expiryBufferSeconds,    // refresh this many seconds early
        String headerName,           // defaults to "Authorization"
        String headerPrefix  ,
        String secretKeyIdPointer // defaults to "Bearer "
) {}
