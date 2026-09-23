package com.tovarika.tech.images.application;

import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class ImageGeneratorFactory {
    private final Map<String,ImageGenerator> adapters;
    // Spring bean names are registry keys. New adapters require registration, never edits to this class.
    public ImageGeneratorFactory(Map<String,ImageGenerator> adapters) { this.adapters=Map.copyOf(adapters); }
    public ImageGenerator create(String provider) {
        ImageGenerator generator=provider==null?null:adapters.get(provider.toLowerCase(Locale.ROOT));
        if (generator==null) throw new IllegalArgumentException("Unknown image provider");
        return generator;
    }
    public ImageEditor editor(String provider) {
        if (create(provider) instanceof ImageEditor editor) return editor;
        throw new IllegalArgumentException("Provider does not support image editing");
    }
}
