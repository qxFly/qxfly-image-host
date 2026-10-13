package com.pichost.storage.impl;

import com.pichost.common.BizException;
import com.pichost.storage.StorageObject;
import com.pichost.storage.StorageService;
import com.pichost.storage.StorageSetting;
import com.pichost.storage.StorageType;
import com.pichost.storage.UploadResult;
import com.pichost.storage.support.ObjectKeys;
import com.qiniu.common.QiniuException;
import com.qiniu.http.Response;
import com.qiniu.storage.BucketManager;
import com.qiniu.storage.Configuration;
import com.qiniu.storage.Region;
import com.qiniu.storage.UploadManager;
import com.qiniu.util.Auth;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 七牛云 Kodo。
 */
@Component
public class QiniuKodoStorageService implements StorageService {

    private static final Map<String, Region> REGIONS = Map.of(
            "z0", Region.region0(),
            "z1", Region.region1(),
            "z2", Region.region2(),
            "na0", Region.regionNa0(),
            "as0", Region.regionAs0());

    @Override
    public StorageType type() {
        return StorageType.QINIU_KODO;
    }

    @Override
    public UploadResult upload(StorageSetting setting, StorageObject object) {
        Configuration configuration = configuration(setting);
        Auth auth = auth(setting);
        UploadManager uploadManager = new UploadManager(configuration);
        String token = auth.uploadToken(setting.get("bucket"));
        try {
            Response response = uploadManager.put(object.content(), object.objectKey(), token, null,
                    object.contentType() == null ? "application/octet-stream" : object.contentType(), false);
            if (!response.isOK()) {
                throw new BizException("上传到七牛云失败: " + response.bodyString());
            }
        } catch (QiniuException e) {
            throw new BizException("上传到七牛云失败: " + e.getMessage());
        }
        return UploadResult.of(object.objectKey(), ObjectKeys.joinUrl(setting.get("domain"), object.objectKey()));
    }

    @Override
    public void delete(StorageSetting setting, String objectKey) {
        try {
            new BucketManager(auth(setting), configuration(setting)).delete(setting.get("bucket"), objectKey);
        } catch (QiniuException e) {
            throw new BizException("从七牛云删除失败: " + e.getMessage());
        }
    }

    @Override
    public void test(StorageSetting setting) {
        try {
            new BucketManager(auth(setting), configuration(setting)).buckets();
        } catch (QiniuException e) {
            throw new BizException("连接七牛云失败: " + e.getMessage());
        }
    }

    @Override
    public String publicUrl(StorageSetting setting, String objectKey) {
        return ObjectKeys.joinUrl(setting.get("domain"), objectKey);
    }

    private Auth auth(StorageSetting setting) {
        return Auth.create(setting.get("accessKey"), setting.get("secretKey"));
    }

    private Configuration configuration(StorageSetting setting) {
        Region region = REGIONS.getOrDefault(setting.get("region", "z0"), Region.region0());
        return new Configuration(region);
    }
}
