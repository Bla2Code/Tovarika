package com.tovarika.tech.infrastructure.storage;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("tovarika.storage.minio")
public record MinioStorageProperties(
		@NotBlank String endpoint,
		@NotBlank String accessKey,
		@NotBlank String secretKey,
		@NotBlank String bucket
) {
}
