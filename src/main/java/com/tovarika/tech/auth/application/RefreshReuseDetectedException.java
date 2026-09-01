package com.tovarika.tech.auth.application;

public final class RefreshReuseDetectedException extends AuthException {

    public RefreshReuseDetectedException() {
        super(AuthErrorCode.REFRESH_TOKEN_REUSE_DETECTED, 401, "Refresh token reuse detected");
    }
}
