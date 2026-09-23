package com.tovarika.tech.trial.application;

import com.tovarika.tech.auth.application.AuthErrorCode;
import com.tovarika.tech.auth.application.AuthException;
import com.tovarika.tech.auth.application.port.AuthenticationRateLimiter;
import com.tovarika.tech.auth.application.port.OpaqueTokenService;
import com.tovarika.tech.trial.domain.TrialSession;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@EnableConfigurationProperties(TrialProperties.class)
public class TrialSessionService {
    private final TrialSessionStore store;
    private final OpaqueTokenService tokens;
    private final AuthenticationRateLimiter rateLimiter;
    private final TrialProperties properties;
    private final Clock clock;

    public TrialSessionService(TrialSessionStore store, OpaqueTokenService tokens,
            AuthenticationRateLimiter rateLimiter, TrialProperties properties, Clock clock) {
        this.store = store;
        this.tokens = tokens;
        this.rateLimiter = rateLimiter;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional(noRollbackFor = AuthException.class)
    public Bootstrap bootstrap(String rawCookie, String rateLimitSubject) {
        // Even an empty cookie is a presented credential, never a request for a new allowance.
        if (rawCookie != null) {
            return new Bootstrap(get(rawCookie), null);
        }
        rateLimiter.check(AuthenticationRateLimiter.Scope.TRIAL_CREATE, rateLimitSubject, properties.creationRule());
        Instant now = clock.instant();
        TrialSession session = new TrialSession("trial_" + UUID.randomUUID().toString().replace("-", ""),
                3, 0, now, now.plus(properties.ttl()));
        String token = tokens.generate();
        store.create(session, tokens.hash(token));
        return new Bootstrap(session, token);
    }

    @Transactional(readOnly = true)
    public TrialSession get(String rawCookie) {
        if (rawCookie == null || rawCookie.isBlank()) {
            throw unavailable();
        }
        return store.findAvailable(tokens.hash(rawCookie), clock.instant()).orElseThrow(this::unavailable);
    }

    private AuthException unavailable() {
        return AuthException.unauthorized(AuthErrorCode.TRIAL_SESSION_NOT_FOUND,
                "Trial session is missing, invalid, expired or converted");
    }

    public record Bootstrap(TrialSession session, String rawToken) {
        @Override
        public String toString() { return "Bootstrap[session=" + session.id() + "]"; }
    }
}
