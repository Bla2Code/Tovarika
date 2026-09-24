package com.tovarika.tech.analyses.infrastructure;

import com.tovarika.tech.analyses.application.AnalysisProvider;
import com.tovarika.tech.analyses.domain.AnalysisResult;
import com.tovarika.tech.images.infrastructure.OpenAiClient;
import com.tovarika.tech.images.infrastructure.OpenAiProperties;
import org.springframework.stereotype.Component;

@Component
public class ChatGPTAnalysisAdapter implements AnalysisProvider {
    private final OpenAiClient client;
    private final OpenAiProperties properties;
    public ChatGPTAnalysisAdapter(OpenAiClient client,OpenAiProperties properties) { this.client=client;this.properties=properties; }
    public AnalysisResult analyze(String operationId,byte[] original,String mediaType) {
        return client.analyze(properties.visionModel(),operationId,original,mediaType);
    }
}
