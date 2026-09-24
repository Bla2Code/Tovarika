package com.tovarika.tech.analyses.domain;

public record AnalysisResult(String title, String description, String idea) {
    public AnalysisResult {
        if (title != null && title.length() > 200 || description == null || description.isBlank()
                || description.length() > 4000 || idea == null || idea.isBlank() || idea.length() > 2000) {
            throw new IllegalArgumentException("Invalid analysis result");
        }
    }
    @Override public String toString() { return "AnalysisResult[redacted]"; }
}
