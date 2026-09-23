package com.tovarika.tech.products.application;

public interface ProductStorage {
    void put(String key, byte[] original, String mediaType);
    byte[] read(String key);
    void delete(String key);
}
