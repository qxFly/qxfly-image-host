package com.pichost.storage.impl;

import com.pichost.common.BizException;
import com.pichost.storage.StorageObject;
import com.pichost.storage.StorageService;
import com.pichost.storage.StorageSetting;
import com.pichost.storage.StorageType;
import com.pichost.storage.UploadResult;
import com.pichost.storage.support.ObjectKeys;
import com.qcloud.cos.COSClient;
import com.qcloud.cos.ClientConfig;
import com.qcloud.cos.auth.BasicCOSCredentials;
import com.qcloud.cos.auth.COSCredentials;
import com.qcloud.cos.exception.CosClientException;
import com.qcloud.cos.model.ObjectMetadata;
import com.qcloud.cos.region.Region;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;

/**
 * 腾讯云 COS。
 */
@Component
public class TencentCosStorageService implements StorageService {

    @Override
    public StorageType type() {
        return StorageType.TENCENT_COS;
    }

    @Override
    public UploadResult upload(StorageSetting setting, StorageObject object) {
        COSClient client = createClient(setting);
        try {
            ObjectMetadata metadata = new ObjectMetadata();
            metadata.setContentLength(object.size());
            metadata.setContentType(object.contentType() == null ? "application/octet-stream" : object.contentType());
            client.putObject(setting.get("bucket"), object.objectKey(),
                    new ByteArrayInputStream(object.content()), metadata);
            return UploadResult.of(object.objectKey(), publicUrl(setting, object.objectKey()));
        } catch (CosClientException e) {
            throw new BizException("上传到腾讯云 COS 失败: " + e.getMessage());
        } finally {
            client.shutdown();
        }
    }

    @Override
    public void delete(StorageSetting setting, String objectKey) {
        COSClient client = createClient(setting);
        try {
            client.deleteObject(setting.get("bucket"), objectKey);
        } catch (CosClientException e) {
            throw new BizException("从腾讯云 COS 删除失败: " + e.getMessage());
        } finally {
            client.shutdown();
        }
    }

    @Override
    public void test(StorageSetting setting) {
        COSClient client = createClient(setting);
        try {
            if (!client.doesBucketExist(setting.get("bucket"))) {
                throw new BizException("存储桶不存在或无权访问: " + setting.get("bucket"));
            }
        } catch (CosClientException e) {
            throw new BizException("连接腾讯云 COS 失败: " + e.getMessage());
        } finally {
            client.shutdown();
        }
    }

    private COSClient createClient(StorageSetting setting) {
        COSCredentials credentials = new BasicCOSCredentials(setting.get("secretId"), setting.get("secretKey"));
        ClientConfig config = new ClientConfig(new Region(setting.get("region")));
        config.setConnectionTimeout(10_000);
        config.setSocketTimeout(60_000);
        return new COSClient(credentials, config);
    }

    @Override
    public String publicUrl(StorageSetting setting, String objectKey) {
        String customDomain = setting.get("customDomain");
        if (customDomain != null) {
            return ObjectKeys.joinUrl(customDomain, objectKey);
        }
        String region = setting.get("region");
        return "https://" + setting.get("bucket") + ".cos." + region + ".myqcloud.com/" + objectKey;
    }
}
