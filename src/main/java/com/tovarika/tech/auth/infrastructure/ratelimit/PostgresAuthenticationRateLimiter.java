package com.tovarika.tech.auth.infrastructure.ratelimit;

import com.tovarika.tech.auth.application.AuthErrorCode;
import com.tovarika.tech.auth.application.AuthException;
import com.tovarika.tech.auth.application.AuthenticationProperties;
import com.tovarika.tech.auth.application.port.AuthenticationRateLimiter;
import com.tovarika.tech.auth.application.port.OpaqueTokenService;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class PostgresAuthenticationRateLimiter implements AuthenticationRateLimiter {

    private static final String UPSERT = """
            INSERT INTO authentication_rate_limits(scope, subject_hash, window_started_at, attempts, expires_at)
            VALUES (?, ?, ?, 1, ?)
            ON CONFLICT (scope, subject_hash, window_started_at)
            DO UPDATE SET attempts = authentication_rate_limits.attempts + 1, expires_at = EXCLUDED.expires_at
            RETURNING attempts
            """;

    private final JdbcTemplate jdbcTemplate;
    private final OpaqueTokenService tokenService;
    private final Clock clock;

    public PostgresAuthenticationRateLimiter(
            JdbcTemplate jdbcTemplate, OpaqueTokenService tokenService, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.tokenService = tokenService;
        this.clock = clock;
    }

    @Override
    public void check(Scope scope, String subject, AuthenticationProperties.RateLimit.Rule rule) {
        Instant now = clock.instant();
        Instant windowStart = windowStart(now, rule.window());
        Integer attempts = jdbcTemplate.queryForObject(
                UPSERT,
                Integer.class,
                scope.name(),
                tokenService.hash(subject),
                Timestamp.from(windowStart),
                Timestamp.from(windowStart.plus(rule.window()).plus(rule.window())));
        if (attempts != null && attempts > rule.maxAttempts()) {
            throw new AuthException(AuthErrorCode.RATE_LIMITED, 429, "Authentication rate limit exceeded");
        }
    }

    private Instant windowStart(Instant now, Duration window) {
        long seconds = window.toSeconds();
        if (seconds <= 0) {
            throw new IllegalStateException("Rate limit window must be positive");
        }
        return Instant.ofEpochSecond((now.getEpochSecond() / seconds) * seconds);
    }
}
