package com.tovarika.tech.images.infrastructure;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

@Component
public class ProductAnalysisPrompt {
    private static final String RESOURCE = "prompts/product-analysis-v1.txt";
    private final String text;

    public ProductAnalysisPrompt() {
        this(load());
    }

    ProductAnalysisPrompt(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalStateException("Product analysis prompt is empty");
        }
        this.text = text;
    }

    public String text() {
        return text;
    }

    private static String load() {
        try (var input = new ClassPathResource(RESOURCE).getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot load product analysis prompt", failure);
        }
    }
}
