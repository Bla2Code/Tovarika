package com.tovarika.tech.auth.application.port;

public interface AuthenticationMessageSender {

    void sendEmailVerification(String email, String rawToken);

    void sendPasswordReset(String email, String rawToken);
}
