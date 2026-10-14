package com.qxfly.imagehost.storage.impl;

import com.aliyun.oss.ClientException;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.OSSException;
import com.aliyun.oss.model.ObjectMetadata;
import com.qxfly.imagehost.common.BizException;
import com.qxfly.imagehost.storage.StorageObject;
import com.qxfly.imagehost.storage.StorageService;
import com.qxfly.imagehost.storage.StorageSetting;
import com.qxfly.imagehost.storage.StorageType;
import com.qxfly.imagehost.storage.UploadResult;
import com.qxfly.imagehost.storage.support.ObjectKeys;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;

/**
 * 阿里云 OSS。
 */
@Component
public class AliyunOssStorageService implements StorageService {

    @Override
    public StorageType type() {
        return StorageType.ALIYUN_OSS;
    }

    @Override
    public UploadResult upload(StorageSetting setting, StorageObject object) {
        String bucket = setting.get("bucket");
        OSS client = createClient(setting);
        try {
            ObjectMetadata metadata = new ObjectMetadata();
            metadata.setContentLength(object.size());
            metadata.setContentType(object.contentType() == null ? "application/octet-stream" : object.contentType());
            client.putObject(bucket, object.objectKey(), new ByteArrayInputStream(object.content()), metadata);
            return UploadResult.of(object.objectKey(), publicUrl(setting, object.objectKey()));
        } catch (OSSException | ClientException e) {
            throw new BizException("上传到阿里云 OSS 失败: " + e.getMessage());
        } finally {
            client.shutdown();
        }
    }

    @Override
    public void delete(StorageSetting setting, String objectKey) {
        OSS client = createClient(setting);
        try {
            client.deleteObject(setting.get("bucket"), objectKey);
        } catch (OSSException | ClientException e) {
            throw new BizException("从阿里云 OSS 删除失败: " + e.getMessage());
        } finally {
            client.shutdown();
        }
    }

    @Override
    public void test(StorageSetting setting) {
        OSS client = createClient(setting);
        try {
            if (!client.doesBucketExist(setting.get("bucket"))) {
                throw new BizException("存储桶不存在或无权访问: " + setting.get("bucket"));
            }
        } catch (OSSException | ClientException e) {
            throw new BizException("连接阿里云 OSS 失败: " + e.getMessage());
        } finally {
            client.shutdown();
        }
    }

    private OSS createClient(StorageSetting setting) {
        return new OSSClientBuilder().build(
                setting.get("endpoint"),
                setting.get("accessKeyId"),
                setting.get("accessKeySecret"));
    }

    @Override
    public String publicUrl(StorageSetting setting, String objectKey) {
        String customDomain = setting.get("customDomain");
        if (customDomain != null) {
            return ObjectKeys.joinUrl(customDomain, objectKey);
        }
        String endpoint = setting.get("endpoint", "").replaceFirst("^https?://", "");
        return "https://" + setting.get("bucket") + "." + endpoint + "/" + objectKey;
    }
}
