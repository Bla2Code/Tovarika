package com.tovarika.tech.trial.application;

import com.tovarika.tech.auth.application.AuthenticationProperties;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("tovarika.trial")
public record TrialProperties(
        @DefaultValue("30d") Duration ttl,
        @DefaultValue("10") int creationMaxAttempts,
        @DefaultValue("1h") Duration creationWindow) {
    public TrialProperties {
        if (ttl == null || ttl.toSeconds() <= 0 || creationMaxAttempts <= 0
                || creationWindow == null || creationWindow.toSeconds() <= 0) {
            throw new IllegalArgumentException("Trial TTL and creation rate limit must be positive");
        }
    }

    public AuthenticationProperties.RateLimit.Rule creationRule() {
        return new AuthenticationProperties.RateLimit.Rule(creationMaxAttempts, creationWindow);
    }
}
