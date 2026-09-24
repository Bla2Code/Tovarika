package com.tovarika.tech.images.application;

public interface ImageGenerator {
    GeneratedImage generate(String prompt, int width, int height);
}
