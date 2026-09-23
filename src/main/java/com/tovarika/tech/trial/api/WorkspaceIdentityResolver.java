package com.tovarika.tech.trial.api;

import com.tovarika.tech.auth.api.AuthenticationCookieService;
import com.tovarika.tech.auth.api.RequestAuthenticationContext;
import com.tovarika.tech.auth.application.AuthErrorCode;
import com.tovarika.tech.auth.application.AuthException;
import com.tovarika.tech.trial.application.TrialSessionService;
import org.springframework.stereotype.Component;

@Component
public class WorkspaceIdentityResolver {
    private final RequestAuthenticationContext request;
    private final AuthenticationCookieService cookies;
    private final TrialSessionService trials;

    public WorkspaceIdentityResolver(RequestAuthenticationContext request, AuthenticationCookieService cookies,
            TrialSessionService trials) {
        this.request = request;
        this.cookies = cookies;
        this.trials = trials;
    }

    public Identity resolve() {
        var principal = request.optionalPrincipal();
        if (principal != null) {
            return new Identity(principal.userId(), null);
        }
        String rawCookie = request.cookie(cookies.trialCookieName());
        if (rawCookie == null || rawCookie.isBlank()) {
            throw AuthException.unauthorized(AuthErrorCode.AUTHENTICATION_REQUIRED, "Authentication is required");
        }
        return new Identity(null, trials.get(rawCookie).id());
    }

    public record Identity(String userId, String trialSessionId) {}
}
