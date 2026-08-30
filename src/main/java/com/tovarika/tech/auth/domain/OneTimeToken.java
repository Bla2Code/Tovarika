package com.tovarika.tech.auth.domain;

import java.time.Instant;

public record OneTimeToken(
        String id,
        String userId,
        String identityId,
        String tokenHash,
        Instant createdAt,
        Instant expiresAt,
        Instant consumedAt) {

    public boolean isUsableAt(Instant now) {
        return consumedAt == null && now.isBefore(expiresAt);
    }
}
