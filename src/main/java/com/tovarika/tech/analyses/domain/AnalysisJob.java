package com.tovarika.tech.analyses.domain;

import java.time.Instant;

public record AnalysisJob(String id, String productId, String analysisId, String status, int attempt,
        Instant createdAt, Instant startedAt, Instant finishedAt) {}
