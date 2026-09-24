package com.tovarika.tech.images.application;

/** Optional editing capability; generation-only adapters need not implement it. */
public interface ImageEditor {
    GeneratedImage edit(byte[] original, String mediaType, String prompt, int width, int height);
}
