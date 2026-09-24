package com.tovarika.tech.images.application;

public record GeneratedImage(byte[] bytes, String mediaType, int width, int height, boolean stub) {
    public GeneratedImage { bytes=bytes.clone(); }
    @Override public byte[] bytes() { return bytes.clone(); }
    @Override public String toString() { return "GeneratedImage[width="+width+", height="+height+", stub="+stub+"]"; }
}
