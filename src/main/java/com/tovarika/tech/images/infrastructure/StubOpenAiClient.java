package com.tovarika.tech.images.infrastructure;

import com.tovarika.tech.analyses.domain.AnalysisResult;
import com.tovarika.tech.images.application.GeneratedImage;
import com.tovarika.tech.images.application.GenerationRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * Offline transport requested for initial development. Never sends images, prompts or keys to a network.
 */
@Component
@ConditionalOnProperty(name = "tovarika.ai.openai.mode", havingValue = "stub", matchIfMissing = true)
public class StubOpenAiClient implements OpenAiClient {
    public StubOpenAiClient(OpenAiProperties properties) {}

    public GeneratedImage generate(String mainModel, String imageModel, GenerationRequest request) {
        return placeholder(request.width(), request.height());
    }

    public GeneratedImage edit(String model, byte[] original, String mediaType, String prompt, int width, int height) {
        return placeholder(width, height);
    }

    public AnalysisResult analyze(String model, String operationId, byte[] original, String mediaType) {
        if (original == null || original.length == 0) throw new IllegalArgumentException("Source image is empty");
        return new AnalysisResult("[STUB] Товар", "[STUB] Демонстрационный анализ; содержимое изображения моделью не исследовано.",
                "[STUB] Демонстрационная идея карточки для проверки рабочего процесса.");
    }

    private GeneratedImage placeholder(int width, int height) {
        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        try {
            graphics.setColor(new Color(235, 238, 242));
            graphics.fillRect(0, 0, width, height);
            graphics.setColor(Color.DARK_GRAY);
            graphics.drawString("STUB - no OpenAI request", Math.min(10, width / 4), Math.min(25, height / 2));
        } finally {
            graphics.dispose();
        }
        try (var output = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", output);
            return new GeneratedImage(output.toByteArray(), "image/png", width, height, true);
        } catch (IOException impossible) {
            throw new IllegalStateException("Cannot create placeholder");
        }
    }
}
