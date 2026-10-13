package com.rsa.routeforge.config;

import org.apache.camel.CamelContext;
import org.apache.camel.component.http.HttpComponent;
import org.apache.camel.spring.boot.CamelContextConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Applies outbound HTTP timeouts centrally, so no call site can forget them.
 * <p>
 * Before this, {@code producerTemplate.requestBodyAndHeaders(...)} ran with Camel's defaults —
 * effectively unbounded — and a single slow downstream could pin every request thread.
 * <p>
 * Both {@code http} and {@code https} resolve to {@link HttpComponent}, and routes use both
 * (see {@code testroute.json} vs {@code getquotetest.json}), so both schemes are configured.
 */
@Configuration
public class EngineHttpClientConfig {

    private static final Logger log = LoggerFactory.getLogger(EngineHttpClientConfig.class);
    private static final List<String> HTTP_SCHEMES = List.of("http", "https");

    @Bean
    public CamelContextConfiguration engineHttpTimeouts(EngineProperties props) {
        return new CamelContextConfiguration() {
            @Override
            public void beforeApplicationStart(CamelContext camelContext) {
                EngineProperties.Http http = props.getHttp();
                for (String scheme : HTTP_SCHEMES) {
                    HttpComponent component = camelContext.getComponent(scheme, HttpComponent.class);
                    component.setConnectTimeout(http.getConnectTimeoutMs());
                    component.setResponseTimeout(http.getResponseTimeoutMs());
                    component.setConnectionRequestTimeout(http.getPoolTimeoutMs());
                    component.setSoTimeout(http.getSocketTimeoutMs());
                }
                log.info("Outbound HTTP timeouts applied to {}: connect={}ms response={}ms pool={}ms socket={}ms",
                        HTTP_SCHEMES, http.getConnectTimeoutMs(), http.getResponseTimeoutMs(),
                        http.getPoolTimeoutMs(), http.getSocketTimeoutMs());
            }

            @Override
            public void afterApplicationStart(CamelContext camelContext) {
                // nothing to do
            }
        };
    }
}
