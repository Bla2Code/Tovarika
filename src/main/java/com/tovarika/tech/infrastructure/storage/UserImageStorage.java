package com.tovarika.tech.infrastructure.storage;

import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import java.io.InputStream;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class UserImageStorage {

	private static final long UNKNOWN_OBJECT_SIZE = -1;
	private static final long MULTIPART_PART_SIZE = 10 * 1024 * 1024;

	private final MinioClient minioClient;
	private final MinioStorageProperties properties;

	public UserImageStorage(MinioClient minioClient, MinioStorageProperties properties) {
		this.minioClient = minioClient;
		this.properties = properties;
	}

	public String store(UUID userId, String extension, String contentType, long size, InputStream content)
			throws Exception {
		var objectKey = objectKey(userId, extension);
		minioClient.putObject(PutObjectArgs.builder()
				.bucket(properties.bucket())
				.object(objectKey)
				.contentType(contentType)
				.stream(content, size >= 0 ? size : UNKNOWN_OBJECT_SIZE, MULTIPART_PART_SIZE)
				.build());
		return objectKey;
	}

	public GetObjectResponse load(String objectKey) throws Exception {
		return minioClient.getObject(GetObjectArgs.builder()
				.bucket(properties.bucket())
				.object(objectKey)
				.build());
	}

	public void delete(String objectKey) throws Exception {
		minioClient.removeObject(RemoveObjectArgs.builder()
				.bucket(properties.bucket())
				.object(objectKey)
				.build());
	}

	private String objectKey(UUID userId, String extension) {
		var normalizedExtension = extension == null ? "" : extension.replaceAll("[^A-Za-z0-9]", "");
		var suffix = normalizedExtension.isBlank() ? "" : "." + normalizedExtension.toLowerCase();
		return "users/" + userId + "/images/" + UUID.randomUUID() + suffix;
	}
}
