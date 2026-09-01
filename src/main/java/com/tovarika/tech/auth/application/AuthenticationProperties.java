package com.tovarika.tech.auth.application;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("tovarika.security")
public record AuthenticationProperties(
        Jwt jwt,
        Refresh refresh,
        Token token,
        Cookie cookie,
        Cors cors,
        Password password,
        OAuth oauth,
        Mail mail,
        RateLimit rateLimit) {

    public record Jwt(URI issuer, String audience, Duration accessTokenTtl, String secretBase64) {}

    public record Refresh(Duration absoluteTtl, Duration inactivityTtl) {}

    public record Token(Duration passwordResetTtl) {}

    public record Cookie(
            String refreshName,
            String oauthName,
            String trialName,
            boolean secure,
            String sameSite,
            String path) {}

    public record Cors(List<String> allowedOrigins) {}

    public record Password(boolean breachedCheckEnabled, URI pwnedApiUrl) {}

    public record OAuth(Yandex yandex) {
        public record Yandex(
                String clientId,
                String clientSecret,
                URI authorizationUri,
                URI tokenUri,
                URI userInfoUri,
                URI redirectUri,
                Duration attemptTtl,
                List<String> allowedReturnPathPrefixes) {}
    }

    public record Mail(String from, URI uiBaseUri) {}

    public record RateLimit(
            Rule login,
            Rule register,
            Rule passwordReset,
            Rule oauthStart,
            Rule oauthCallback,
            Rule refresh) {
        public record Rule(int maxAttempts, Duration window) {}
    }
}
