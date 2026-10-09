package com.tovarika.tech.images.application;

/** Optional editing capability; generation-only adapters need not implement it. */
public interface ImageEditor {
    GeneratedImage edit(byte[] original, String mediaType, String prompt, int width, int height);
    default GeneratedImage edit(ImageEditInput input) {
        if(input.mask().length>0 || input.transparentBackground())
            throw new UnsupportedOperationException("Masked or transparent editing is unavailable");
        return edit(input.original(),input.mediaType(),input.prompt(),input.width(),input.height());
    }
}
