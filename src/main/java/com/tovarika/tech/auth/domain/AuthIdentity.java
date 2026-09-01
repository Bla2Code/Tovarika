package com.tovarika.tech.auth.domain;

import java.time.Instant;

public record AuthIdentity(
        String id,
        String userId,
        AuthProvider provider,
        String providerUserId,
        String passwordHash,
        Instant createdAt,
        Instant updatedAt) {}
