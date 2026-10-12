package com.tovarika.tech.images.application;

import java.util.List;

public record GenerationRequest(String prompt, List<InputImage> images, int width, int height) {
    public GenerationRequest {
        images = images == null ? List.of() : List.copyOf(images);
    }

    public record InputImage(byte[] bytes, String mediaType, String role) {
        public InputImage {
            bytes = bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }
}
