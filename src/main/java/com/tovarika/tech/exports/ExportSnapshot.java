package com.tovarika.tech.exports;

import java.time.Instant;
import java.util.List;

public record ExportSnapshot(String cardId, String versionId, long imageRevision, String assetId,
        String mediaType, int sizeBytes, String fileName, String storageKey,
        Integer width, Integer height, Boolean hasAlpha, Instant createdAt) {
    public record Expectation(String cardId, String versionId, Long imageRevision) {}
    public record Request(List<String> cardIds, String format, String packaging, List<Expectation> expectedImages) {}
    public record Job(String id, String exportId, String projectId, String status, String failureCode,
            int attempt, Instant createdAt, Instant startedAt, Instant finishedAt) {}
    public record Export(String id, String projectId, String jobId, String format, String packaging,
            String fileName, List<ExportSnapshot> items, String artifactId, String status, String errorCode,
            Instant createdAt, Instant expiresAt) {}
    public record Artifact(String id, String purpose, String mediaType, long sizeBytes, String storageKey,
            Integer width, Integer height, Boolean hasAlpha, Instant createdAt) {}
}
