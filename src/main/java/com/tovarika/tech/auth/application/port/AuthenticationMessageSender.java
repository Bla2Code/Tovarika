package com.tovarika.tech.auth.application.port;

public interface AuthenticationMessageSender {
    void sendPasswordReset(String email, String rawToken);
}
