package com.tovarika.tech.templates;

public record TemplateGenerationView(
        String id,
        String recipeJson,
        String referenceAssetId,
        String referenceStorageKey,
        String referenceMediaType) {
    public boolean hasReference() {
        return referenceAssetId != null && referenceStorageKey != null && referenceMediaType != null;
    }
}
