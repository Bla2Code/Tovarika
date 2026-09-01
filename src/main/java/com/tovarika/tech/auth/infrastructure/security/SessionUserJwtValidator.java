package com.tovarika.tech.auth.infrastructure.security;

import com.tovarika.tech.auth.application.AuthenticationProperties;
import com.tovarika.tech.auth.application.port.AuthenticationStore;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

@Component
public class SessionUserJwtValidator implements OAuth2TokenValidator<Jwt> {

    private static final OAuth2Error INVALID = new OAuth2Error("invalid_token", "Access token is invalid", null);
    private final AuthenticationStore store;
    private final AuthenticationProperties properties;
    private final Clock clock;

    public SessionUserJwtValidator(
            AuthenticationStore store, AuthenticationProperties properties, Clock clock) {
        this.store = store;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        String userId = jwt.getSubject();
        String sessionId = jwt.getClaimAsString("sid");
        String tokenId = jwt.getId();
        List<String> audience = jwt.getAudience();
        if (userId == null
                || !userId.matches("^usr_[A-Za-z0-9]+$")
                || sessionId == null
                || !sessionId.matches("^ses_[A-Za-z0-9]+$")
                || tokenId == null
                || tokenId.isBlank()
                || audience == null
                || !audience.contains(properties.jwt().audience())) {
            return OAuth2TokenValidatorResult.failure(INVALID);
        }
        Instant now = clock.instant();
        boolean active = store.isAccessSessionActive(
                userId, sessionId, now, now.minus(properties.refresh().inactivityTtl()));
        return active ? OAuth2TokenValidatorResult.success() : OAuth2TokenValidatorResult.failure(INVALID);
    }
}
