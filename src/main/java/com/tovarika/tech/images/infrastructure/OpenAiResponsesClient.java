package com.tovarika.tech.images.infrastructure;

import com.tovarika.tech.analyses.domain.AnalysisResult;
import com.tovarika.tech.images.application.GeneratedImage;
import com.tovarika.tech.images.application.GenerationRequest;
import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.awt.Color;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
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
import javax.imageio.ImageIO;
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
    public GeneratedImage generate(String mainModel, String imageModel, GenerationRequest request) {
        validateGeneration(mainModel, imageModel, request);
        List<Map<String, Object>> content = new java.util.ArrayList<>();
        content.add(Map.of("type", "input_text", "text", request.prompt()));
        for (GenerationRequest.InputImage image : request.images()) {
            String dataUrl = "data:" + image.mediaType() + ";base64,"
                    + Base64.getEncoder().encodeToString(image.bytes());
            content.add(Map.of("type", "input_image", "image_url", dataUrl, "detail", "high"));
        }
        String requestId = "card-" + UUID.randomUUID();
        String generationSize = generationSize(imageModel, request.width(), request.height());
        Map<String, Object> body = Map.of(
                "model", mainModel,
                "store", false,
                "instructions", "Generate one complete product card on a " + generationSize + " canvas. "
                        + "The final output is " + request.width() + "x" + request.height() + " pixels. "
                        + "Adapt the composition specified in the input prompt to this canvas, preserving its visual hierarchy and alignment. "
                        + "Fit the entire visible source product and all overlay text inside the canvas unless the input prompt permits a discernible source detail close-up; then fit that selected fragment and all text. "
                        + "Keep at least 5% inset from each edge for the product, headings, feature text and badges. "
                        + "Reduce font size, wrap long headings and reflow blocks as needed; never clip text or product details. "
                        + "Balance the composition within the available space. The result will be proportionally fitted "
                        + "and centered on the final canvas without cropping or stretching.",
                "input", List.of(Map.of("role", "user", "content", content)),
                "tools", List.of(Map.of(
                        "type", "image_generation",
                        "model", imageModel,
                        "action", "edit",
                        "size", generationSize,
                        "quality", "auto")));
        try {
            String responseBody = client.post()
                    .uri("/responses")
                    .header("X-Client-Request-Id", requestId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);
            JsonNode response = responseBody == null ? null : mapper.readTree(responseBody);
            if (response == null || !"completed".equals(response.path("status").asText())) {
                throw new IllegalArgumentException("OpenAI image response is incomplete");
            }
            JsonNode output = response.path("output");
            if (output.isArray()) {
                for (JsonNode item : output) {
                    if ("image_generation_call".equals(item.path("type").asText())
                            && item.path("result").isString()) {
                        byte[] image = Base64.getDecoder().decode(item.path("result").asText());
                        if (image.length == 0) throw new IllegalArgumentException("Empty image result");
                        return normalize(image, request.width(), request.height());
                    }
                }
            }
            throw new IllegalArgumentException("OpenAI response has no generated image");
        } catch (RestClientException | JacksonException | IllegalArgumentException failure) {
            throw new IllegalStateException("OpenAI image generation request failed");
        }
    }

    private String generationSize(String imageModel, int width, int height) {
        long pixels = (long) width * height;
        boolean customSizeModel = imageModel.matches("gpt-image-(2|2\\.5-(sunburst|flare))(-\\d{4}-\\d{2}-\\d{2})?");
        if (customSizeModel && width % 16 == 0 && height % 16 == 0
                && Math.max(width, height) <= 3L * Math.min(width, height)
                && pixels >= 655_360 && pixels <= 8_294_400) {
            return width + "x" + height;
        }
        return width == height ? "1024x1024" : width > height ? "1536x1024" : "1024x1536";
    }

    private GeneratedImage normalize(byte[] encoded, int targetWidth, int targetHeight) {
        try {
            BufferedImage source = ImageIO.read(new ByteArrayInputStream(encoded));
            if (source == null) throw new IOException("Unsupported generated image");
            double scale = Math.min((double) targetWidth / source.getWidth(),
                    (double) targetHeight / source.getHeight());
            int scaledWidth = Math.max(1, Math.min(targetWidth, (int) Math.round(source.getWidth() * scale)));
            int scaledHeight = Math.max(1, Math.min(targetHeight, (int) Math.round(source.getHeight() * scale)));
            int x = (targetWidth - scaledWidth) / 2;
            int y = (targetHeight - scaledHeight) / 2;
            BufferedImage canvas = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);
            var graphics = canvas.createGraphics();
            try {
                // A neutral matte preserves the full card, including transparent pixels, without duplicating edge text.
                graphics.setColor(Color.WHITE);
                graphics.fillRect(0, 0, targetWidth, targetHeight);
                graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                        RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                graphics.drawImage(source, x, y, scaledWidth, scaledHeight, null);
            } finally {
                graphics.dispose();
            }
            try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                ImageIO.write(canvas, "png", output);
                return new GeneratedImage(output.toByteArray(), "image/png", targetWidth, targetHeight, false);
            }
        } catch (IOException failure) {
            throw new IllegalArgumentException("Generated image cannot be decoded");
        }
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

    private void validateGeneration(String mainModel, String imageModel, GenerationRequest request) {
        if (mainModel == null || mainModel.isBlank() || imageModel == null || imageModel.isBlank()
                || request == null || request.prompt() == null || request.prompt().isBlank()
                || request.prompt().length() > 8000 || request.images().isEmpty() || request.images().size() > 2
                || request.width() <= 0 || request.height() <= 0
                || request.width() > 3840 || request.height() > 3840) {
            throw new IllegalArgumentException("Invalid OpenAI image generation request");
        }
        for (GenerationRequest.InputImage image : request.images()) {
            if (image.bytes().length == 0 || image.bytes().length > 10_485_760
                    || !SUPPORTED_MEDIA_TYPES.contains(image.mediaType())) {
                throw new IllegalArgumentException("Invalid OpenAI input image");
            }
        }
    }

    private static String validateConfigurationAndReadApiKey(OpenAiProperties properties) {
        if (properties.apiKeyFile() == null || properties.apiKeyFile().isBlank()
                || properties.visionModel() == null || properties.visionModel().isBlank()
                || properties.imageModel() == null || properties.imageModel().isBlank()
                || properties.baseUrl() == null || properties.baseUrl().isBlank()) {
            throw new IllegalStateException(
                    "OPENAI_API_KEY_FILE, OPENAI_VISION_MODEL, OPENAI_IMAGE_MODEL and OPENAI_BASE_URL are required in live mode");
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
