package com.tovarika.tech.images.application;

import com.tovarika.tech.billing.usage.AiCallContext;

import org.springframework.stereotype.Service;

/** Client example: the application depends on interfaces and the registry, not a concrete API adapter. */
@Service
public class ImageGenerationService {
    private final ImageGeneratorFactory factory;
    public ImageGenerationService(ImageGeneratorFactory factory) { this.factory=factory; }
    public GeneratedImage generate(String provider, GenerationRequest request) {
        ImageGenerator generator=factory.create(provider);
        return generator.generate(request);
    }
    public GeneratedImage edit(String provider, ImageEditInput input) {
        return factory.editor(provider).edit(input);
    }
    public GeneratedImage edit(String provider, byte[] original, String mediaType, String prompt, int width, int height) {
        return factory.editor(provider).edit(original,mediaType,prompt,width,height);
    }
    public GeneratedImage generate(String provider,GenerationRequest request,AiCallContext context) {
        return factory.create(provider).generate(request,context);
    }
    public GeneratedImage edit(String provider,ImageEditInput input,AiCallContext context) {
        return factory.editor(provider).edit(input,context);
    }
}
