package com.tovarika.tech.auth.domain;

import java.time.Instant;

public record AuthenticationSession(
        String id,
        String userId,
        String tokenFamilyId,
        Instant createdAt,
        Instant lastActivityAt,
        Instant absoluteExpiresAt,
        Instant revokedAt,
        String deviceName,
        String approximateIp) {

    public boolean isRevoked() {
        return revokedAt != null;
    }
}
