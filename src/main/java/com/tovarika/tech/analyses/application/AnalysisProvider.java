package com.tovarika.tech.analyses.application;

import com.tovarika.tech.billing.usage.AiCallContext;

import com.tovarika.tech.analyses.domain.AnalysisResult;

public interface AnalysisProvider {
    // Recovery preserves job correlation, but may call the provider again; no exactly-once guarantee.
    AnalysisResult analyze(String operationId, byte[] original, String mediaType);
    default AnalysisResult analyze(String operationId,byte[] original,String mediaType,AiCallContext context) {
        return analyze(operationId,original,mediaType);
    }
}
