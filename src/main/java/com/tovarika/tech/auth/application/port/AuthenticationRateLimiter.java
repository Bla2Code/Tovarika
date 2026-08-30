package com.tovarika.tech.auth.application.port;

import com.tovarika.tech.auth.application.AuthenticationProperties;

public interface AuthenticationRateLimiter {

    void check(Scope scope, String subject, AuthenticationProperties.RateLimit.Rule rule);

    enum Scope {
        LOGIN,
        REGISTER,
        VERIFICATION,
        PASSWORD_RESET,
        OAUTH_START,
        OAUTH_CALLBACK,
        REFRESH
    }
}
