package com.tovarika.tech.cards;

import java.time.Instant;

public record CardGenerationJob(
        String id,
        String projectId,
        String cardId,
        String status,
        String failureCode,
        int attempt,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt) {}
