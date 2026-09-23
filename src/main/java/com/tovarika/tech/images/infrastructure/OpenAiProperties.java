package com.tovarika.tech.images.infrastructure;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("tovarika.ai.openai")
public record OpenAiProperties(@DefaultValue("stub") String mode,
        @DefaultValue("https://api.openai.com/v1") String baseUrl,
        @DefaultValue("") String apiKey,
        @DefaultValue("") String imageModel,
        @DefaultValue("") String visionModel) {
    @Override public String toString() { return "OpenAiProperties[mode="+mode+"]"; }
}
