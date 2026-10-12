package com.tovarika.tech.products.application;

public interface ProductStorage {
    void put(String key, byte[] original, String mediaType);
    byte[] read(String key);
    void delete(String key);
    default java.io.InputStream open(String key) {
        return new java.io.ByteArrayInputStream(read(key));
    }
    default void putStream(String key, java.io.InputStream input, long size, String mediaType) {
        try {
            if (size < 1 || size > 110 * 1024 * 1024) throw new IllegalArgumentException("Invalid export size");
            byte[] bytes = input.readNBytes((int) size + 1);
            if (bytes.length != size) throw new IllegalStateException("Incomplete export");
            put(key, bytes, mediaType);
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("Export storage unavailable", failure);
        }
    }
}
