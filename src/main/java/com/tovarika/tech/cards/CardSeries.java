package com.tovarika.tech.cards;

import com.tovarika.tech.shared.application.ApiFailure;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Server-only series snapshot; never returned as an HTTP DTO. */
public record CardSeries(JsonNode snapshot) {
    public static CardSeries parse(ObjectMapper mapper, String json) {
        JsonNode node = mapper.readTree(json);
        if (node == null || !node.isObject() || !node.path("variants").isArray()) {
            throw new ApiFailure(422, "TEMPLATE_NOT_READY", "Invalid series snapshot");
        }
        return new CardSeries(node);
    }

    public String templateId() { return snapshot.path("templateId").asText(); }
    public String name() { return snapshot.path("name").asText(); }
    public String referenceId() {
        return snapshot.path("referenceAssetId").isString() ? snapshot.path("referenceAssetId").asText() : null;
    }
    public String recipe() { return snapshot.path("recipe").toString(); }
    public String json() { return snapshot.toString(); }

    public JsonNode variant(int position) {
        for (JsonNode variant : snapshot.path("variants")) {
            if (variant.path("position").asInt() == position) return variant;
        }
        return null;
    }

    public JsonNode variant(String id) {
        for (JsonNode variant : snapshot.path("variants")) {
            if (variant.path("id").asText().equals(id)) return variant;
        }
        return null;
    }

    public CardSeries withLegacyStyle(ObjectMapper mapper, String recipe, String referenceId) {
        ObjectNode copy = (ObjectNode) snapshot.deepCopy();
        copy.set("recipe", mapper.readTree(recipe));
        if (referenceId == null) copy.putNull("referenceAssetId");
        else copy.put("referenceAssetId", referenceId);
        return new CardSeries(copy);
    }
}
