package com.tovarika.tech.cards.editing.domain;

import java.time.Instant;

public record ImageEditJob(String id, String projectId, String cardId, String type, String status,
        String failureCode, int attempt, String baseVersionId, long expectedRevision, String payload,
        String resultVersionId, String resultAssetId, Long resultRevision,
        Instant createdAt, Instant startedAt, Instant finishedAt) {}
