package com.tovarika.tech.cards;

import java.time.Instant;

public record CardView(
        String id,
        String projectId,
        int position,
        String status,
        String aspectRatio,
        String templateId,
        String errorCode,
        CardAssetView image,
        Instant createdAt,
        Instant updatedAt) {}
