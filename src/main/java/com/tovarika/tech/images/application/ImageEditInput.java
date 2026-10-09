package com.tovarika.tech.images.application;

/** Alpha mask: transparent pixels are editable; an empty mask means whole-image editing. */
public record ImageEditInput(byte[] original, String mediaType, String prompt, int width, int height,
        byte[] mask, boolean transparentBackground) {
    public ImageEditInput {
        original=original.clone(); mask=mask==null?new byte[0]:mask.clone();
    }
    @Override public byte[] original() { return original.clone(); }
    @Override public byte[] mask() { return mask.clone(); }
}
