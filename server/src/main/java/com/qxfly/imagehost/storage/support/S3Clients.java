package com.qxfly.imagehost.storage.support;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;

import java.net.URI;

/**
 * 构造 S3 协议客户端，供 Cloudflare R2 / MinIO / 其它 S3 兼容存储共用。
 */
public final class S3Clients {

    private S3Clients() {
    }

    public static S3Client create(String endpoint, String region, String accessKey, String secretKey, boolean pathStyle) {
        S3ClientBuilder builder = S3Client.builder()
                .region(Region.of(region == null || region.isBlank() ? "us-east-1" : region))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)))
                .forcePathStyle(pathStyle);
        if (endpoint != null && !endpoint.isBlank()) {
            builder.endpointOverride(URI.create(endpoint.trim()));
        }
        return builder.build();
    }
}
