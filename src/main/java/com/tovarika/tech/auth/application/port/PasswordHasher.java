package com.tovarika.tech.auth.application.port;

public interface PasswordHasher {

    String hash(String password);

    boolean matches(String password, String passwordHash);

    void performDummyVerification(String password);
}
