package com.tovarika.tech.products.infrastructure;

import com.tovarika.tech.products.application.ProductStorage;
import com.tovarika.tech.infrastructure.storage.MinioStorageProperties;
import com.tovarika.tech.shared.application.ApiFailure;
import io.minio.*;
import java.io.ByteArrayInputStream;
import org.springframework.stereotype.Component;

@Component
public class MinioProductStorage implements ProductStorage {
    private final MinioClient client;
    private final MinioStorageProperties properties;
    public MinioProductStorage(MinioClient client, MinioStorageProperties properties) {
        this.client = client; this.properties = properties;
    }
    public void put(String key, byte[] original, String mediaType) {
        try {
            client.putObject(PutObjectArgs.builder().bucket(properties.bucket()).object(key)
                    .contentType(mediaType).stream(new ByteArrayInputStream(original), (long) original.length, -1L).build());
        } catch (Exception failure) { throw unavailable(); }
    }
    public byte[] read(String key) {
        try (var input = client.getObject(GetObjectArgs.builder().bucket(properties.bucket()).object(key).build())) {
            byte[] result = input.readNBytes(10485761);
            if (result.length > 10485760) { throw unavailable(); }
            return result;
        } catch (Exception failure) { throw unavailable(); }
    }
    public void delete(String key) {
        try { client.removeObject(RemoveObjectArgs.builder().bucket(properties.bucket()).object(key).build()); }
        catch (Exception failure) { throw unavailable(); }
    }
    private ApiFailure unavailable() { return new ApiFailure(500, "INTERNAL_ERROR", "Image storage unavailable"); }
}
