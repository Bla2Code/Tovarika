package com.tovarika.tech.images.infrastructure;

import com.tovarika.tech.images.application.GeneratedImage;
import com.tovarika.tech.images.application.GenerationRequest;
import com.tovarika.tech.analyses.domain.AnalysisResult;

/** Transport boundary. The initial implementation is deliberately offline, selected by configuration. */
public interface OpenAiClient {
    GeneratedImage generate(String mainModel, String imageModel, GenerationRequest request);
    GeneratedImage edit(String model, byte[] original, String mediaType, String prompt, int width, int height);
    default GeneratedImage edit(String mainModel, String imageModel, com.tovarika.tech.images.application.ImageEditInput input) {
        throw new UnsupportedOperationException("Advanced image editing is unavailable");
    }
    AnalysisResult analyze(String model, String operationId, byte[] original, String mediaType);
}
