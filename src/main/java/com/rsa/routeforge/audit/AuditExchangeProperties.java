package com.rsa.routeforge.audit;

/**
 * Exchange property keys used to carry inbound HTTP metadata from the route to the engine.
 * <p>
 * They exist because {@code MarketplaceRoutesBuilder} strips {@code CamelHttp*} headers before the
 * engine's processor runs, so by the time an audit record is built the real method and query string
 * are gone. Properties survive {@code removeHeaders}, and — unlike a renamed header — cannot leak
 * into {@code ctx.input} via {@code parseIncoming}.
 * <p>
 * They live in this package rather than in {@code routes} so that {@code config} can read them
 * without the engine depending on the route layer above it.
 */
public final class AuditExchangeProperties {

    public static final String HTTP_METHOD = "engine.audit.httpMethod";
    public static final String HTTP_URI = "engine.audit.httpUri";
    public static final String HTTP_QUERY = "engine.audit.httpQuery";

    private AuditExchangeProperties() {
    }
}
