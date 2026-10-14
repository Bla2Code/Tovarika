package com.tovarika.tech.images.application;

import com.tovarika.tech.billing.usage.AiCallContext;

public interface ImageGenerator {
    GeneratedImage generate(GenerationRequest request);
    default GeneratedImage generate(GenerationRequest request,AiCallContext context) {
        return generate(request);
    }
}
