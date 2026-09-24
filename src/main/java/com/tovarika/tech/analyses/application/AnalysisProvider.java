package com.tovarika.tech.analyses.application;

import com.tovarika.tech.analyses.domain.AnalysisResult;

public interface AnalysisProvider {
    // The same operationId is supplied on recovery; the provider must deduplicate billable operations.
    AnalysisResult analyze(String operationId, byte[] original, String mediaType);
}
