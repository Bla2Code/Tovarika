package com.tovarika.tech.project;

import java.time.Instant;

record ProjectView(
        String id,
        String name,
        String productId,
        String selectedTemplateId,
        String defaultAspectRatio,
        int cardCount,
        Instant createdAt,
        Instant updatedAt,
        ProjectAssetView previewImage) {}
