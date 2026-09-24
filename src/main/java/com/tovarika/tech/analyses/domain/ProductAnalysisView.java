package com.tovarika.tech.analyses.domain;

import java.time.Instant;

public record ProductAnalysisView(
        String id,
        String productId,
        String title,
        String description,
        String idea,
        String generationPrompt,
        int revision,
        Instant createdAt,
        Instant updatedAt) {}
