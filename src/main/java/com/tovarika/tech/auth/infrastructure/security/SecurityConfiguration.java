package com.tovarika.tech.auth.infrastructure.security;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.tovarika.api.publicapi.model.ErrorCodeDto;
import com.tovarika.tech.auth.application.AuthenticationProperties;
import java.time.Clock;
import java.util.Base64;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.client.RestClient;
import org.springframework.web.filter.CorsFilter;

@Configuration
@EnableConfigurationProperties(AuthenticationProperties.class)
public class SecurityConfiguration {

    @Bean
    Clock authenticationClock() {
        return Clock.systemUTC();
    }

    @Bean
    RestClient.Builder authenticationRestClientBuilder() {
        return RestClient.builder();
    }

    @Bean
    JwtEncoder jwtEncoder(AuthenticationProperties properties) {
        SecretKey key = signingKey(properties);
        return new NimbusJwtEncoder(new ImmutableSecret<>(key));
    }

    @Bean
    JwtDecoder jwtDecoder(AuthenticationProperties properties, SessionUserJwtValidator sessionValidator) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(signingKey(properties))
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(properties.jwt().issuer().toString()), sessionValidator));
        return decoder;
    }

    @Bean
    SecurityFilterChain authenticationSecurityFilterChain(
            HttpSecurity http,
            AllowedOriginFilter allowedOriginFilter,
            SecurityErrorWriter errorWriter,
            CorsConfigurationSource corsConfigurationSource)
            throws Exception {
        http.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        http.cors(cors -> cors.configurationSource(corsConfigurationSource));
        // Bearer authentication is not ambient-cookie authentication. The three refresh-cookie mutations are
        // protected by strict Origin validation in AllowedOriginFilter, matching the public contract without
        // introducing an undocumented synchronizer-token requirement.
        http.csrf(csrf -> csrf.disable());
        http.authorizeHttpRequests(authorize -> authorize
                .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                .requestMatchers(
                        "/api/v1/auth/register",
                        "/api/v1/auth/login",
                        "/api/v1/auth/refresh",
                        "/api/v1/auth/logout",
                        "/api/v1/auth/email-verification-requests",
                        "/api/v1/auth/email-verification-confirmations",
                        "/api/v1/auth/password-reset-requests",
                        "/api/v1/auth/password-reset-confirmations",
                        "/api/v1/auth/oauth/yandex/authorizations",
                        "/api/v1/auth/oauth/yandex/callback",
                        "/api/v1/trial-session",
                        "/api/v1/billing/plans",
                        "/api/v1/billing/offers",
                        "/api/v1/template-categories",
                        "/api/v1/templates",
                        "/openapi/**",
                        "/v3/api-docs/**",
                        "/swagger-ui.html",
                        "/swagger-ui/**")
                .permitAll()
                .anyRequest()
                .authenticated());
        http.oauth2ResourceServer(oauth -> oauth.jwt(Customizer.withDefaults())
                .authenticationEntryPoint((request, response, failure) -> {
                    ErrorCodeDto code = request.getHeader(HttpHeaders.AUTHORIZATION) == null
                            ? ErrorCodeDto.AUTHENTICATION_REQUIRED
                            : ErrorCodeDto.ACCESS_TOKEN_INVALID;
                    errorWriter.write(request, response, 401, code, "Authentication is required");
                })
                .accessDeniedHandler((request, response, failure) ->
                        errorWriter.write(request, response, 403, ErrorCodeDto.FORBIDDEN, "Access is forbidden")));
        http.addFilterBefore(allowedOriginFilter, CorsFilter.class);
        return http.build();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(AuthenticationProperties properties) {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(properties.cors().allowedOrigins());
        configuration.setAllowCredentials(true);
        configuration.setAllowedMethods(java.util.List.of("GET", "POST", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(java.util.List.of("Authorization", "Content-Type", "X-Request-Id"));
        configuration.setExposedHeaders(java.util.List.of("X-Request-Id"));
        configuration.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    private SecretKey signingKey(AuthenticationProperties properties) {
        String configured = properties.jwt().secretBase64();
        if (configured == null || configured.isBlank()) {
            throw new IllegalStateException("JWT_SECRET_BASE64 must be configured");
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(configured);
        } catch (IllegalArgumentException invalidBase64) {
            throw new IllegalStateException("JWT_SECRET_BASE64 must be valid Base64", invalidBase64);
        }
        if (decoded.length < 32) {
            throw new IllegalStateException("JWT signing key must contain at least 256 bits");
        }
        return new SecretKeySpec(decoded, "HmacSHA256");
    }
}
