package com.pichost.storage.impl;

import com.pichost.storage.StorageSetting;
import com.pichost.storage.StorageType;
import com.pichost.storage.support.S3Clients;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * Cloudflare R2（S3 兼容，固定 region=auto，使用虚拟主机风格寻址）。
 */
@Component
public class CloudflareR2StorageService extends AbstractS3StorageService {

    @Override
    public StorageType type() {
        return StorageType.CLOUDFLARE_R2;
    }

    @Override
    protected S3Client createClient(StorageSetting setting) {
        String endpoint = setting.get("endpoint");
        if (endpoint == null) {
            endpoint = "https://" + setting.get("accountId") + ".r2.cloudflarestorage.com";
        }
        return S3Clients.create(endpoint,
                setting.get("region", "auto"),
                setting.get("accessKeyId"),
                setting.get("secretAccessKey"),
                false);
    }

    @Override
    protected String publicBaseUrl(StorageSetting setting) {
        return setting.get("publicBaseUrl");
    }
}
