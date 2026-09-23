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
    public GeneratedImage generate(String prompt,int width,int height) {
        validate(prompt,width,height);
        return client.generate(properties.imageModel(),prompt,width,height);
    }
    public GeneratedImage edit(byte[] original,String mediaType,String prompt,int width,int height) {
        validate(prompt,width,height);
        if (original==null || original.length==0 || original.length>10485760
                || !java.util.Set.of("image/png","image/jpeg","image/webp").contains(mediaType))
            throw new IllegalArgumentException("Unsupported source image");
        return client.edit(properties.imageModel(),original,mediaType,prompt,width,height);
    }
    private void validate(String prompt,int width,int height) {
        if (prompt==null || prompt.isBlank() || prompt.length()>8000 || width<=0 || height<=0
                || width>4096 || height>4096 || (long)width*height>8_388_608)
            throw new IllegalArgumentException("Invalid image request");
    }
}
