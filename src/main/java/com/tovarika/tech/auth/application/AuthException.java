package com.tovarika.tech.auth.application;

public class AuthException extends RuntimeException {

    private final AuthErrorCode code;
    private final int status;

    public AuthException(AuthErrorCode code, int status, String safeMessage) {
        super(safeMessage);
        this.code = code;
        this.status = status;
    }

    public AuthErrorCode code() {
        return code;
    }

    public int status() {
        return status;
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
}
