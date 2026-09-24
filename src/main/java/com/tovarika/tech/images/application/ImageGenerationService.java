package com.tovarika.tech.images.application;

import org.springframework.stereotype.Service;

/** Client example: the application depends on interfaces and the registry, not a concrete API adapter. */
@Service
public class ImageGenerationService {
    private final ImageGeneratorFactory factory;
    public ImageGenerationService(ImageGeneratorFactory factory) { this.factory=factory; }
    public GeneratedImage generate(String provider, String prompt, int width, int height) {
        ImageGenerator generator=factory.create(provider);
        return generator.generate(prompt,width,height);
    }
    public GeneratedImage edit(String provider, byte[] original, String mediaType, String prompt, int width, int height) {
        return factory.editor(provider).edit(original,mediaType,prompt,width,height);
    }
}
