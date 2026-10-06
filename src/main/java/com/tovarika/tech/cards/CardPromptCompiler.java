package com.tovarika.tech.cards;

import com.tovarika.tech.shared.application.ApiFailure;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class CardPromptCompiler {
    private final ObjectMapper mapper;

    public CardPromptCompiler(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public String fromTemplate(String productDescription, String recipeJson) {
        JsonNode recipe;
        try {
            recipe = mapper.readTree(recipeJson);
        } catch (JacksonException invalid) {
            throw new ApiFailure(422, "TEMPLATE_NOT_READY", "Template recipe is invalid");
        }
        if (recipe == null || !recipe.isObject() || recipe.path("schemaVersion").asInt() != 1) {
            throw new ApiFailure(422, "TEMPLATE_NOT_READY", "Template recipe is unsupported");
        }
        List<String> sections = new ArrayList<>();
        sections.add(base(productDescription));
        add(sections, "Scene", recipe.path("scene"));
        add(sections, "Composition", recipe.path("composition"));
        add(sections, "Lighting", recipe.path("lighting"));
        add(sections, "Visual style", recipe.path("style"));
        if (recipe.path("palette").isArray()) {
            List<String> palette = new ArrayList<>();
            recipe.path("palette").forEach(value -> { if (value.isString()) palette.add(value.asText()); });
            if (!palette.isEmpty()) sections.add("Palette: " + String.join(", ", palette));
        }
        List<String> avoid = new ArrayList<>();
        if (recipe.path("avoid").isArray()) {
            recipe.path("avoid").forEach(value -> { if (value.isString()) avoid.add(value.asText()); });
        }
        sections.add("Image 1 is the source product: preserve its identity, geometry, colors, labels and visible details.");
        sections.add("Image 2 is a layout and style reference. Replace its demonstration product with the source product. Recompose the layout for the requested output ratio while preserving the reference alignment, relative placement and visual hierarchy.");
        sections.add("Preserve the layout and visual hierarchy of any reference headings, feature text and badges. Rewrite every overlay text block using the factual product description, consistent with Image 1 and in the language of that description. Omit a block when no supported content is available.");
        sections.add("Do not copy the reference product name, brand, overlay text, claims, prices or color variants. Preserve labels and branding on the source product itself. Do not add garment pieces or product views unsupported by the source image.");
        sections.add("Use the reference palette for background and decoration only, without recoloring the source product or inventing available color variants.");
        if (!avoid.isEmpty()) sections.add("Avoid: " + String.join(", ", avoid));
        sections.add(layout());
        sections.add("Return one finished marketplace product-card image. Do not invent price, brand, specifications or benefits.");
        return checked(String.join("\n", sections));
    }

    public String fromUserPrompt(String productDescription, String userPrompt) {
        return checked(base(productDescription)
                + "\nUser visual direction: " + userPrompt.strip()
                + "\nImage 1 is the source product. Preserve its identity, geometry, colors, labels and visible details."
                + "\n" + layout()
                + "\nReturn one finished marketplace product-card image. Do not invent price, brand, specifications or benefits.");
    }

    private String base(String description) {
        return "Create a marketplace product card using only the source product image and this factual product description: "
                + description.strip();
    }

    private String layout() {
        return "Fit the entire visible source product and every overlay text block inside the output canvas with at least 5% safe margins on all sides. "
                + "Keep headings and badges fully readable. Wrap long text, reduce font size and reposition blocks to fit; do not crop, stretch or let elements overflow the canvas. "
                + "Balance the product and text within the layout; leave room above the heading and below the product.";
    }

    private void add(List<String> sections, String label, JsonNode value) {
        if (value.isString() && !value.asText().isBlank()) sections.add(label + ": " + value.asText().strip());
    }

    private String checked(String prompt) {
        if (prompt.length() > 8000) throw new ApiFailure(422, "VALIDATION_ERROR", "Compiled prompt is too long");
        return prompt;
    }
}
