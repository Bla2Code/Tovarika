package com.tovarika.tech.cards.editing.domain;

import java.util.Set;
import java.util.List;

/** Persisted operation parameters; source identity is stored separately on the job. */
public record ImageEdit(String kind, String prompt, Rect rect, String foreground, String aspectRatio, String mode, BrushMask mask) {
    // Existing queued jobs and callers do not have a brush mask.
    public ImageEdit(String kind, String prompt, Rect rect, String foreground, String aspectRatio, String mode) {
        this(kind,prompt,rect,foreground,aspectRatio,mode,null);
    }
    public record BrushMask(String kind, List<Stroke> strokes) {
        public void validate() {
            if (!"brush".equals(kind) || strokes == null || strokes.isEmpty() || strokes.size() > 64)
                throw new IllegalArgumentException("A brush mask with 1 to 64 strokes is required");
            int total = 0;
            for (var stroke : strokes) {
                if (stroke == null || !Double.isFinite(stroke.radius()) || stroke.radius() < .002 || stroke.radius() > .25
                        || stroke.points() == null || stroke.points().isEmpty() || stroke.points().size() > 1024)
                    throw new IllegalArgumentException("Invalid brush stroke");
                total += stroke.points().size();
                if (total > 8192) throw new IllegalArgumentException("Brush mask exceeds 8192 points");
                for (var point : stroke.points()) {
                    if (point == null || !Double.isFinite(point.x()) || !Double.isFinite(point.y())
                            || point.x() < 0 || point.x() > 1 || point.y() < 0 || point.y() > 1)
                        throw new IllegalArgumentException("Invalid normalized brush point");
                }
            }
        }
    }
    public record Stroke(double radius, List<Point> points) {}
    public record Point(double x, double y) {}
    public record Rect(double x, double y, double width, double height) {
        public void validate() {
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(width) || !Double.isFinite(height)
                    || x < 0 || y < 0 || width <= 0 || height <= 0 || x + width > 1 + 1e-9 || y + height > 1 + 1e-9)
                throw new IllegalArgumentException("Invalid normalized rectangle");
        }
    }
    public void validate() {
        if (kind == null) throw new IllegalArgumentException("Operation kind is required");
        switch (kind) {
            case "entire", "region" -> {
                if (prompt == null || prompt.isBlank() || prompt.length() > 8000)
                    throw new IllegalArgumentException("Prompt must contain 1 to 8000 characters");
                if (kind.equals("region")) {
                    if (rect == null) throw new IllegalArgumentException("Rectangle is required");
                    rect.validate();
                }
            }
            case "erase" -> {
                if (mask == null) throw new IllegalArgumentException("A brush mask is required");
                mask.validate();
            }
            case "remove_background" -> {
                if (!Set.of("preserve_graphics", "product_only").contains(foreground == null ? "" : foreground))
                    throw new IllegalArgumentException("Foreground policy is required");
            }
            case "resize" -> {
                if (!Set.of("1:1", "3:4", "4:5", "16:9").contains(aspectRatio == null ? "" : aspectRatio)
                        || !Set.of("fit_pad", "crop", "outpaint").contains(mode == null ? "" : mode))
                    throw new IllegalArgumentException("Resize ratio and mode are required");
            }
            default -> throw new IllegalArgumentException("Unsupported image operation");
        }
    }
}
