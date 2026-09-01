package com.tovarika.tech.auth.api;

import com.tovarika.tech.auth.application.AuthenticationProperties;
import java.time.Duration;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

@Component
public class AuthenticationCookieService {

    private final AuthenticationProperties properties;

    public AuthenticationCookieService(AuthenticationProperties properties) {
        this.properties = properties;
    }

    public ResponseCookie createRefresh(String rawToken) {
        return cookie(
                properties.cookie().refreshName(), rawToken, properties.refresh().absoluteTtl(), true);
    }

    public ResponseCookie clearRefresh() {
        return clear(properties.cookie().refreshName());
    }

    public ResponseCookie createOAuthCorrelation(String rawToken) {
        return cookie(
                properties.cookie().oauthName(),
                rawToken,
                properties.oauth().yandex().attemptTtl(),
                true);
    }

    public ResponseCookie clearOAuthCorrelation() {
        return clear(properties.cookie().oauthName());
    }

    public ResponseCookie clearTrial() {
        return clear(properties.cookie().trialName());
    }

    public String refreshCookieName() {
        return properties.cookie().refreshName();
    }

    public String oauthCookieName() {
        return properties.cookie().oauthName();
    }

    public String trialCookieName() {
        return properties.cookie().trialName();
    }

    private ResponseCookie cookie(String name, String value, Duration maxAge, boolean httpOnly) {
        return ResponseCookie.from(name, value)
                .httpOnly(httpOnly)
                .secure(properties.cookie().secure())
                .sameSite(properties.cookie().sameSite())
                .path(properties.cookie().path())
                .maxAge(maxAge)
                .build();
    }

    private ResponseCookie clear(String name) {
        return ResponseCookie.from(name, "")
                .httpOnly(true)
                .secure(properties.cookie().secure())
                .sameSite(properties.cookie().sameSite())
                .path(properties.cookie().path())
                .maxAge(Duration.ZERO)
                .build();
    }
}
