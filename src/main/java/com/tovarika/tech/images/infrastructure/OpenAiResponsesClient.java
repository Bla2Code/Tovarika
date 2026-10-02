package com.tovarika.tech.images.infrastructure;

import com.tovarika.tech.analyses.domain.AnalysisResult;
import com.tovarika.tech.images.application.GeneratedImage;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
@ConditionalOnProperty(name = "tovarika.ai.openai.mode", havingValue = "live")
public class OpenAiResponsesClient implements OpenAiClient {
    private static final long MAX_API_KEY_BYTES = 8_192;
    private static final Set<String> SUPPORTED_MEDIA_TYPES = Set.of("image/png", "image/jpeg", "image/webp");
    private final ProductAnalysisPrompt prompt;
    private final ObjectMapper mapper;
    private final RestClient client;

    public OpenAiResponsesClient(OpenAiProperties properties, ProductAnalysisPrompt prompt,
            ObjectMapper mapper, RestClient.Builder builder) {
        this.prompt = prompt;
        this.mapper = mapper;
        String apiKey = validateConfigurationAndReadApiKey(properties);
        this.client = builder.clone()
                .baseUrl(properties.baseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .build();
    }

    @Override
    public AnalysisResult analyze(String model, String operationId, byte[] original, String mediaType) {
        validateInput(model, operationId, original, mediaType);
        String dataUrl = "data:" + mediaType + ";base64," + Base64.getEncoder().encodeToString(original);
        Map<String, Object> request = Map.of(
                "model", model,
                "store", false,
                "instructions", prompt.text(),
                "input", List.of(Map.of(
                        "role", "user",
                        "content", List.of(
                                Map.of("type", "input_text", "text", "Проанализируй приложенное изображение товара."),
                                Map.of("type", "input_image", "image_url", dataUrl, "detail", "high")))),
                "text", Map.of("format", outputFormat()),
                "max_output_tokens", 1200,
                "metadata", Map.of("tovarika_operation_id", operationId));
        try {
            String responseBody = client.post()
                    .uri("/responses")
                    .header("X-Client-Request-Id", operationId + "-" + UUID.randomUUID())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(String.class);
            return parse(responseBody == null ? null : mapper.readTree(responseBody));
        } catch (RestClientException | JacksonException | IllegalArgumentException failure) {
            throw new IllegalStateException("OpenAI analysis request failed");
        }
    }

    @Override
    public GeneratedImage generate(String model, String prompt, int width, int height) {
        throw new UnsupportedOperationException("Live OpenAI image generation is not implemented");
    }

    @Override
    public GeneratedImage edit(String model, byte[] original, String mediaType, String prompt, int width, int height) {
        throw new UnsupportedOperationException("Live OpenAI image editing is not implemented");
    }

    private AnalysisResult parse(JsonNode response) throws JacksonException {
        if (response == null || !"completed".equals(response.path("status").asText())) {
            throw new IllegalArgumentException("OpenAI response is incomplete");
        }
        String output = null;
        JsonNode items = response.path("output");
        if (items.isArray()) {
            for (JsonNode item : items) {
                JsonNode contents = item.path("content");
                if (!"message".equals(item.path("type").asText()) || !contents.isArray()) continue;
                for (JsonNode content : contents) {
                    if ("refusal".equals(content.path("type").asText())) {
                        throw new IllegalArgumentException("OpenAI refused the analysis");
                    }
                    if ("output_text".equals(content.path("type").asText()) && content.path("text").isString()) {
                        output = content.path("text").asText();
                        break;
                    }
                }
                if (output != null) break;
            }
        }
        if (output == null || output.isBlank()) {
            throw new IllegalArgumentException("OpenAI response has no structured output");
        }
        JsonNode result = mapper.readTree(output);
        if (result == null || !result.isObject()
                || !result.path("description").isString() || !result.path("idea").isString()
                || !(result.path("title").isString() || result.path("title").isNull())) {
            throw new IllegalArgumentException("OpenAI response does not match the analysis contract");
        }
        String title = result.path("title").isNull() ? null : result.path("title").asText();
        return new AnalysisResult(title, result.path("description").asText(), result.path("idea").asText());
    }

    private Map<String, Object> outputFormat() {
        Map<String, Object> schema = Map.of(
                "type", "object",
                "properties", Map.of(
                        "title", Map.of("type", List.of("string", "null"),
                                "description", "Краткое название товара или null, если товар нельзя определить"),
                        "description", Map.of("type", "string",
                                "description", "Фактическое описание видимого товара на русском языке"),
                        "idea", Map.of("type", "string",
                                "description", "Идея визуального оформления карточки товара на русском языке")),
                "required", List.of("title", "description", "idea"),
                "additionalProperties", false);
        return Map.of("type", "json_schema", "name", "product_analysis", "strict", true, "schema", schema);
    }

    private void validateInput(String model, String operationId, byte[] original, String mediaType) {
        if (model == null || model.isBlank() || operationId == null || operationId.isBlank()
                || original == null || original.length == 0 || original.length > 10_485_760
                || !SUPPORTED_MEDIA_TYPES.contains(mediaType)) {
            throw new IllegalArgumentException("Invalid OpenAI analysis request");
        }
    }

    private static String validateConfigurationAndReadApiKey(OpenAiProperties properties) {
        if (properties.apiKeyFile() == null || properties.apiKeyFile().isBlank()
                || properties.visionModel() == null || properties.visionModel().isBlank()
                || properties.baseUrl() == null || properties.baseUrl().isBlank()) {
            throw new IllegalStateException(
                    "OPENAI_API_KEY_FILE, OPENAI_VISION_MODEL and OPENAI_BASE_URL are required in live mode");
        }
        URI baseUrl;
        try {
            baseUrl = URI.create(properties.baseUrl());
        } catch (IllegalArgumentException invalid) {
            throw new IllegalStateException("OPENAI_BASE_URL is invalid");
        }
        if (!baseUrl.isAbsolute() || baseUrl.getHost() == null
                || !("https".equalsIgnoreCase(baseUrl.getScheme()) || isLoopback(baseUrl))) {
            throw new IllegalStateException("OPENAI_BASE_URL must use HTTPS");
        }
        try {
            Path keyFile = Path.of(properties.apiKeyFile());
            if (!Files.isRegularFile(keyFile) || Files.size(keyFile) == 0
                    || Files.size(keyFile) > MAX_API_KEY_BYTES) {
                throw new IllegalStateException("OPENAI_API_KEY_FILE must point to a non-empty secret file");
            }
            String apiKey = Files.readString(keyFile, StandardCharsets.UTF_8).strip();
            if (apiKey.isBlank() || apiKey.indexOf('\n') >= 0 || apiKey.indexOf('\r') >= 0) {
                throw new IllegalStateException("OPENAI_API_KEY_FILE must contain exactly one non-empty line");
            }
            return apiKey;
        } catch (IOException | InvalidPathException failure) {
            throw new IllegalStateException("OPENAI_API_KEY_FILE cannot be read");
        }
    }

    private static boolean isLoopback(URI uri) {
        return "http".equalsIgnoreCase(uri.getScheme())
                && ("localhost".equalsIgnoreCase(uri.getHost()) || "127.0.0.1".equals(uri.getHost()));
    }
}
