package com.tovarika.tech.auth.domain;

import java.time.Instant;

public record RefreshCredential(
        String id,
        String sessionId,
        String tokenHash,
        Instant createdAt,
        Instant consumedAt,
        Instant rotatedAt,
        String replacementTokenId) {

    public boolean isConsumed() {
        return consumedAt != null;
    }
}
