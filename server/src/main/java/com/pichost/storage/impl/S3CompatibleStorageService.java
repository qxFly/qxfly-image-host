package com.pichost.storage.impl;

import com.pichost.storage.StorageSetting;
import com.pichost.storage.StorageType;
import com.pichost.storage.support.S3Clients;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * 任意 S3 兼容存储：MinIO、Ceph RGW、Backblaze B2、自建网关等。
 */
@Component
public class S3CompatibleStorageService extends AbstractS3StorageService {

    @Override
    public StorageType type() {
        return StorageType.S3_COMPATIBLE;
    }

    @Override
    protected S3Client createClient(StorageSetting setting) {
        return S3Clients.create(setting.get("endpoint"),
                setting.get("region", "us-east-1"),
                setting.get("accessKeyId"),
                setting.get("secretAccessKey"),
                setting.getBool("pathStyle", true));
    }

    @Override
    protected String publicBaseUrl(StorageSetting setting) {
        return setting.get("publicBaseUrl");
    }
}
