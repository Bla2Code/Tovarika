package com.tovarika.tech.infrastructure.storage;

import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MinioStorageProperties.class)
public class MinioStorageConfiguration {

	@Bean
	MinioClient minioClient(MinioStorageProperties properties) {
		return MinioClient.builder()
				.endpoint(properties.endpoint())
				.credentials(properties.accessKey(), properties.secretKey())
				.build();
	}

	@Bean
	@ConditionalOnProperty(
			prefix = "tovarika.storage.minio",
			name = "initialize-bucket",
			havingValue = "true",
			matchIfMissing = true
	)
	ApplicationRunner minioBucketInitializer(MinioClient minioClient, MinioStorageProperties properties) {
		return arguments -> {
			var bucket = properties.bucket();
			if (!minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
				minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
			}
		};
	}
}
