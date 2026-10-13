package com.rsa.routeforge.config;


import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

@Configuration
public class CorsConfig {

    @Bean
    public CorsFilter corsFilter() {

        CorsConfiguration config = new CorsConfiguration();
        config.addAllowedOriginPattern("*");
        config.addAllowedHeader("*");
        config.addAllowedMethod("*");

        UrlBasedCorsConfigurationSource source =
                new UrlBasedCorsConfigurationSource();

        source.registerCorsConfiguration(
                "/rsa/payments/callback", config);
        source.registerCorsConfiguration(
                "/rsa/kyc/callback/datafetch", config);
        source.registerCorsConfiguration(
                "/bimasugam/callback", config);
        source.registerCorsConfiguration("/TestBimaSugamPaymentcallback.html", config);

        return new CorsFilter(source);
    }
}