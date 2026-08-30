package com.tovarika.tech.auth.domain;

import java.time.Instant;

public record OAuthAttempt(
        String id,
        AuthProvider provider,
        String stateHash,
        String correlationHash,
        String pkceVerifier,
        String returnPath,
        Instant createdAt,
        Instant expiresAt,
        Instant consumedAt) {

    public boolean isUsableAt(Instant now) {
        return consumedAt == null && now.isBefore(expiresAt);
    }
}
