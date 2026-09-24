package com.tovarika.tech.products.domain;

import java.time.Instant;

public record ProductView(String id, String name, String status, String analysisJobId, Asset asset,
        Instant createdAt, Instant updatedAt) {
    public record Asset(String id, String mediaType, int size, Integer width, Integer height,
            String storageKey, Instant createdAt) {}
}
