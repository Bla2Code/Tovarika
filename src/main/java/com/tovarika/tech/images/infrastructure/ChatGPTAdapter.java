package com.tovarika.tech.images.infrastructure;

import com.tovarika.tech.images.application.*;
import org.springframework.stereotype.Component;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@Component("chatgpt")
@EnableConfigurationProperties(OpenAiProperties.class)
public class ChatGPTAdapter implements ImageGenerator, ImageEditor {
    private final OpenAiClient client;
    private final OpenAiProperties properties;
    public ChatGPTAdapter(OpenAiClient client,OpenAiProperties properties) { this.client=client;this.properties=properties; }
    public GeneratedImage generate(GenerationRequest request) {
        validate(request.prompt(),request.width(),request.height());
        if (request.images().isEmpty() || request.images().size() > 2) {
            throw new IllegalArgumentException("One or two input images are required");
        }
        for (var image : request.images()) {
            if (image.bytes().length == 0 || image.bytes().length > 10485760
                    || !java.util.Set.of("image/png","image/jpeg","image/webp").contains(image.mediaType())) {
                throw new IllegalArgumentException("Unsupported input image");
            }
        }
        return client.generate(properties.visionModel(),properties.imageModel(),request);
    }
    public GeneratedImage edit(byte[] original,String mediaType,String prompt,int width,int height) {
        validate(prompt,width,height);
        if (original==null || original.length==0 || original.length>10485760
                || !java.util.Set.of("image/png","image/jpeg","image/webp").contains(mediaType))
            throw new IllegalArgumentException("Unsupported source image");
        return client.edit(properties.imageModel(),original,mediaType,prompt,width,height);
    }
    public GeneratedImage edit(ImageEditInput input) {
        validate(input.prompt(),input.width(),input.height());
        if(input.original().length==0 || input.original().length>10485760 || input.mask().length>10485760
                || !java.util.Set.of("image/png","image/jpeg","image/webp").contains(input.mediaType()))
            throw new IllegalArgumentException("Unsupported source image or mask");
        return client.edit(properties.visionModel(),properties.imageModel(),input);
    }
    private void validate(String prompt,int width,int height) {
        if (prompt==null || prompt.isBlank() || prompt.length()>8000 || width<=0 || height<=0
                || width>4096 || height>4096 || (long)width*height>8_388_608)
            throw new IllegalArgumentException("Invalid image request");
    }
}
