package com.tovarika.tech.project;

import java.net.URI;
import java.time.Instant;

record ProjectAssetView(
        String id,
        String purpose,
        String mediaType,
        int sizeBytes,
        Integer width,
        Integer height,
        URI url,
        Instant expiresAt,
        Instant createdAt) {}
