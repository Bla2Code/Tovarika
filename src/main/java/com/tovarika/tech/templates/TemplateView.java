package com.tovarika.tech.templates;

public record TemplateView(
        String id,
        String name,
        String categoryId,
        String referenceAssetId,
        boolean favorite,
        int categoryOrder,
        int sortOrder) {}
