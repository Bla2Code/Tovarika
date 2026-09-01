package com.tovarika.tech.auth.domain;

import java.net.URI;
import java.time.Instant;

public record UserAccount(
        String id,
        String email,
        boolean emailVerified,
        String displayName,
        URI avatarUrl,
        UserStatus status,
        Instant createdAt,
        Instant updatedAt) {

    public boolean isActive() {
        return status == UserStatus.ACTIVE;
    }
}
