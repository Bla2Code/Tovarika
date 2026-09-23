package com.tovarika.tech.images.infrastructure;

import com.tovarika.tech.images.application.GeneratedImage;
import com.tovarika.tech.analyses.domain.AnalysisResult;

/** Transport boundary. The initial implementation is deliberately offline, selected by configuration. */
public interface OpenAiClient {
    GeneratedImage generate(String model, String prompt, int width, int height);
    GeneratedImage edit(String model, byte[] original, String mediaType, String prompt, int width, int height);
    AnalysisResult analyze(String model, String operationId, byte[] original, String mediaType);
}
