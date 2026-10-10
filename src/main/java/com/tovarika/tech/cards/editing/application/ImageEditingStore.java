package com.tovarika.tech.cards.editing.application;

import com.tovarika.tech.cards.editing.domain.ImageEditJob;
import java.time.Instant;
import java.util.Optional;

public interface ImageEditingStore {
    record Current(String versionId, long revision, String status, String previousVersionId, String redoVersionId) {}
    record Source(String storageKey, String mediaType, String aspectRatio) {}
    record Output(String id, String key, int size, int width, int height, boolean hasAlpha) {}
    void lockOwnerProject(String projectId, String userId, String trialId);
    Current current(String projectId, String cardId);
    void requireIdle(String projectId);
    Optional<String> replay(String scope, String key, String digest, boolean history);
    void enqueue(String jobId, String projectId, String cardId, String type, String scope, String key,
            String digest, String baseVersionId, long revision, String payload, Instant now);
    void undo(String projectId, String cardId, String scope, String key, String digest, Current current, Instant now);
    void redo(String projectId, String cardId, String scope, String key, String digest, Current current, Instant now);
    Optional<ImageEditJob> findJob(String id, String userId, String trialId);
    Optional<ImageEditJob> claim(Instant now);
    Source source(ImageEditJob job);
    boolean complete(ImageEditJob job, Output output, Instant now);
    boolean fail(ImageEditJob job, String code, Instant now);
}
