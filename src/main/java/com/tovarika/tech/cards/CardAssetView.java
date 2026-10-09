package com.tovarika.tech.cards;

import java.time.Instant;

public record CardAssetView(
        String id,
        String mediaType,
        int sizeBytes,
        Integer width,
        Integer height,
        Instant createdAt,
        Boolean hasAlpha) {}
