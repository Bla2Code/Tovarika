package com.tovarika.tech.analyses.application;

import com.tovarika.tech.analyses.domain.*;
import java.time.Instant;
import java.util.*;

public interface AnalysisStore {
    void lockOwner(String userId, String trialId);
    Product lockProduct(String productId, String userId, String trialId);
    Optional<AnalysisJob> previous(String key, String userId, String trialId);
    void enqueue(AnalysisJob job, String scope, String key);
    Optional<AnalysisJob> ownedJob(String jobId, String userId, String trialId);
    Optional<AnalysisJob> claim(Instant now, Instant leaseUntil);
    Source source(String productId);
    boolean complete(AnalysisJob job, AnalysisResult result, String prompt, Instant now);
    boolean fail(AnalysisJob job, Instant now);
    Optional<ProductAnalysisView> findAnalysis(String productId);
    ProductAnalysisView updateAnalysis(
            String productId, String title, String description, String idea, String prompt, Instant now);
    record Product(String id, String status, String analysisJobId) {}
    record Source(String storageKey, String mediaType) {}
}
