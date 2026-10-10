package com.tovarika.tech.cards;

import com.tovarika.tech.shared.application.ApiFailure;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

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

    public String fromVariant(String description, String sharedRecipe, String variantRecipe, String idea, int position) {
        JsonNode variant = mapper.readTree(variantRecipe);
        if (!variant.isObject() || variant.path("schemaVersion").asInt() != 1
                || !List.of("full_product", "detail").contains(variant.path("framingMode").asText())) {
            throw new ApiFailure(422, "TEMPLATE_NOT_READY", "Variant recipe is invalid");
        }
        JsonNode shared = mapper.readTree(sharedRecipe);
        if (!shared.isObject()) throw new ApiFailure(422, "TEMPLATE_NOT_READY", "Template recipe is invalid");
        ObjectNode recipe = (ObjectNode) shared.deepCopy();
        if (position > 1) recipe.put("composition", variant.path("composition").asText());
        String prompt = fromTemplate(description, recipe.toString());
        if (position > 1) {
            prompt = prompt.replace(
                    "Image 2 is a layout and style reference. Replace its demonstration product with the source product. Recompose the layout for the requested output ratio while preserving the reference alignment, relative placement and visual hierarchy.",
                    "Image 2 defines the shared background, palette, typography, shapes and lighting only. Replace its demonstration product with Image 1. Replace the reference arrangement with the recommended scenario composition; do not retain the cover layout.")
                    .replace(
                    "Preserve the layout and visual hierarchy of any reference headings, feature text and badges.",
                    "Use the selected style for headings, feature text and badges in the new composition.");
            prompt = prompt.replace(layout(),
                    "Default to showing the entire source product. A detail scenario or an explicit close-up in the final user idea may show only a genuinely discernible source fragment. "
                    + "Never reconstruct invisible fibers, seams, labels or reverse views. When that detail is not discernible, use the fallback composition with the full product. "
                    + "Fit the selected fragment or full product and all overlay text inside the canvas with at least 5% margins on every side. Wrap text and reflow blocks without clipping or stretching.");
        }
        List<String> sections = new ArrayList<>();
        sections.add(prompt);
        sections.add("Recommended framing: " + variant.path("framingMode").asText());
        if (variant.path("conditions").isArray()) {
            variant.path("conditions").forEach(condition -> add(sections, "Mandatory factual condition", condition));
        }
        add(sections, "Fallback composition when evidence is insufficient", variant.path("fallbackComposition"));
        sections.add("Final user idea (visual direction, never a source of product facts): " + idea);
        sections.add("The final user idea may override the recommended accent and arrangement. Preserve the shared style, source product identity and all factual conditions. "
                + "Use only description and Image 1 as evidence; do not invent specifications, dimensions, materials, other views, color variants or sets.");
        return checked(String.join("\n", sections));
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
