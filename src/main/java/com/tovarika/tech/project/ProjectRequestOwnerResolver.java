package com.tovarika.tech.project;

import com.tovarika.api.publicapi.model.ErrorCodeDto;
import com.tovarika.tech.auth.api.AuthenticationCookieService;
import com.tovarika.tech.auth.api.RequestAuthenticationContext;
import com.tovarika.tech.auth.application.port.OpaqueTokenService;
import java.time.Clock;
import java.time.Instant;
import java.sql.Timestamp;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
class ProjectRequestOwnerResolver {
    private final RequestAuthenticationContext request;
    private final AuthenticationCookieService cookies;
    private final OpaqueTokenService tokens;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    ProjectRequestOwnerResolver(
            RequestAuthenticationContext request,
            AuthenticationCookieService cookies,
            OpaqueTokenService tokens,
            JdbcTemplate jdbc,
            Clock clock) {
        this.request = request;
        this.cookies = cookies;
        this.tokens = tokens;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    ProjectOwner resolve() {
        RequestAuthenticationContext.Principal principal = request.optionalPrincipal();
        if (principal != null) {
            return ProjectOwner.user(principal.userId());
        }
        String rawTrialToken = request.cookie(cookies.trialCookieName());
        if (rawTrialToken == null || rawTrialToken.isBlank()) {
            throw ProjectException.unauthorized(ErrorCodeDto.AUTHENTICATION_REQUIRED, "Authentication is required");
        }
        String trialId = jdbc.query(
                        """
                        select id from trial_sessions
                        where token_hash = ? and owner_user_id is null and expires_at > ?
                        """,
                        (result, row) -> result.getString("id"),
                        tokens.hash(rawTrialToken),
                        Timestamp.from(Instant.now(clock)))
                .stream()
                .findFirst()
                .orElseThrow(() -> ProjectException.unauthorized(
                        ErrorCodeDto.TRIAL_SESSION_NOT_FOUND, "Trial session not found"));
        return ProjectOwner.trial(trialId);
    }
}
