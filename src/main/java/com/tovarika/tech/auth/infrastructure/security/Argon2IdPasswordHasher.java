package com.tovarika.tech.auth.infrastructure.security;

import com.tovarika.tech.auth.application.port.PasswordHasher;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class Argon2IdPasswordHasher implements PasswordHasher {

    private final Argon2PasswordEncoder encoder = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
    private final String dummyHash = encoder.encode("constant-time-dummy-password-value");

    @Override
    public String hash(String password) {
        return encoder.encode(password);
    }

    @Override
    public boolean matches(String password, String passwordHash) {
        return encoder.matches(password, passwordHash);
    }

    @Override
    public void performDummyVerification(String password) {
        encoder.matches(password, dummyHash);
    }
}
