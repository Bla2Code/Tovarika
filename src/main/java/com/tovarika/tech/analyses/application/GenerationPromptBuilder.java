package com.tovarika.tech.analyses.application;

import com.tovarika.tech.analyses.domain.AnalysisResult;
import org.springframework.stereotype.Component;

@Component
public class GenerationPromptBuilder {
    public String build(AnalysisResult result) {
        return "Create a marketplace product card from the original product image. Preserve product identity.\n"
                + "Product title: " + (result.title() == null ? "" : result.title())
                + "\nProduct description: " + result.description() + "\nVisual idea: " + result.idea();
    }
}
