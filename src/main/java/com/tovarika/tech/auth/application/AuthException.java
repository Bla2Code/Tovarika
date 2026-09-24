package com.tovarika.tech.auth.application;

public class AuthException extends RuntimeException {

    private final AuthErrorCode code;
    private final int status;
    private final Long retryAfterSeconds;

    public AuthException(AuthErrorCode code, int status, String safeMessage) {
        this(code, status, safeMessage, null);
    }

    private AuthException(AuthErrorCode code, int status, String safeMessage, Long retryAfterSeconds) {
        super(safeMessage);
        this.code = code;
        this.status = status;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public AuthErrorCode code() {
        return code;
    }

    public int status() {
        return status;
    }

    public Long retryAfterSeconds() {
        return retryAfterSeconds;
    }

    public static AuthException unauthorized(AuthErrorCode code, String message) {
        return new AuthException(code, 401, message);
    }

    public static AuthException forbidden(AuthErrorCode code, String message) {
        return new AuthException(code, 403, message);
    }

    public static AuthException badRequest(AuthErrorCode code, String message) {
        return new AuthException(code, 400, message);
    }

    public static AuthException conflict(AuthErrorCode code, String message) {
        return new AuthException(code, 409, message);
    }

    public static AuthException unprocessable(String message) {
        return new AuthException(AuthErrorCode.VALIDATION_ERROR, 422, message);
    }

    public static AuthException rateLimited(String message, long retryAfterSeconds) {
        return new AuthException(AuthErrorCode.RATE_LIMITED, 429, message, Math.max(1, retryAfterSeconds));
    }
}
