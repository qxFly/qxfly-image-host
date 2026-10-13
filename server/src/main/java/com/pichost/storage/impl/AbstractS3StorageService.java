package com.pichost.storage.impl;

import com.pichost.common.BizException;
import com.pichost.storage.RemoteObject;
import com.pichost.storage.StorageObject;
import com.pichost.storage.StorageService;
import com.pichost.storage.StorageSetting;
import com.pichost.storage.UploadResult;
import com.pichost.storage.support.ImageTypes;
import com.pichost.storage.support.ObjectKeys;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.core.sync.RequestBody;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.util.ArrayList;
import java.util.List;

/**
 * S3 协议存储后端公共实现。
 */
@Slf4j
public abstract class AbstractS3StorageService implements StorageService {

    protected abstract S3Client createClient(StorageSetting setting);

    protected abstract String publicBaseUrl(StorageSetting setting);

    @Override
    public UploadResult upload(StorageSetting setting, StorageObject object) {
        String bucket = setting.get("bucket");
        try (S3Client client = createClient(setting)) {
            client.putObject(PutObjectRequest.builder()
                            .bucket(bucket)
                            .key(object.objectKey())
                            .contentType(object.contentType() == null ? "application/octet-stream" : object.contentType())
                            .contentLength(object.size())
                            .build(),
                    RequestBody.fromBytes(object.content()));
            return UploadResult.of(object.objectKey(), publicUrl(setting, object.objectKey()));
        } catch (S3Exception e) {
            throw new BizException("上传到【" + type().getLabel() + "】失败: " + errorMessage(e));
        }
    }

    @Override
    public void delete(StorageSetting setting, String objectKey) {
        try (S3Client client = createClient(setting)) {
            client.deleteObject(DeleteObjectRequest.builder()
                    .bucket(setting.get("bucket"))
                    .key(objectKey)
                    .build());
        } catch (S3Exception e) {
            throw new BizException("从【" + type().getLabel() + "】删除失败: " + errorMessage(e));
        }
    }

    @Override
    public void test(StorageSetting setting) {
        try (S3Client client = createClient(setting)) {
            client.headBucket(HeadBucketRequest.builder().bucket(setting.get("bucket")).build());
        } catch (S3Exception e) {
            throw new BizException("连接【" + type().getLabel() + "】失败: " + errorMessage(e));
        }
    }

    @Override
    public String publicUrl(StorageSetting setting, String objectKey) {
        return ObjectKeys.joinUrl(publicBaseUrl(setting), objectKey);
    }

    @Override
    public boolean supportsList() {
        return true;
    }

    @Override
    public boolean supportsDownload() {
        return true;
    }

    @Override
    public byte[] download(StorageSetting setting, String objectKey) {
        try (S3Client client = createClient(setting)) {
            return client.getObjectAsBytes(GetObjectRequest.builder()
                    .bucket(setting.get("bucket"))
                    .key(objectKey)
                    .build()).asByteArray();
        } catch (S3Exception e) {
            throw new BizException("从【" + type().getLabel() + "】下载失败: " + errorMessage(e));
        }
    }

    /** ListObjectsV2 分页列举，用于把桶里已有的图片导入平台 */
    @Override
    public List<RemoteObject> list(StorageSetting setting, String prefix, int limit) {
        String bucket = setting.get("bucket");
        List<RemoteObject> objects = new ArrayList<>();
        try (S3Client client = createClient(setting)) {
            String continuation = null;
            do {
                int maxKeys = Math.min(Math.max(limit - objects.size(), 1), 1000);
                ListObjectsV2Request.Builder builder = ListObjectsV2Request.builder()
                        .bucket(bucket)
                        .maxKeys(maxKeys);
                if (StringUtils.hasText(prefix)) {
                    builder.prefix(prefix);
                }
                if (continuation != null) {
                    builder.continuationToken(continuation);
                }
                ListObjectsV2Response response = client.listObjectsV2(builder.build());
                for (S3Object object : response.contents()) {
                    if (object.key().endsWith("/") || !ImageTypes.isImage(object.key())) {
                        continue;
                    }
                    objects.add(new RemoteObject(object.key(),
                            object.size() == null ? 0L : object.size(),
                            publicUrl(setting, object.key()),
                            object.lastModified() == null ? null : object.lastModified().toString()));
                    if (objects.size() >= limit) {
                        break;
                    }
                }
                continuation = Boolean.TRUE.equals(response.isTruncated())
                        ? response.nextContinuationToken() : null;
            } while (continuation != null && objects.size() < limit);
            if (continuation != null) {
                log.info("列举【{}】达到上限 {} 条，仍有剩余对象未拉取", type().getLabel(), limit);
            }
        } catch (S3Exception e) {
            throw new BizException("列举【" + type().getLabel() + "】文件失败: " + errorMessage(e));
        }
        return objects;
    }

    protected static String errorMessage(S3Exception e) {
        if (e.awsErrorDetails() != null && e.awsErrorDetails().errorMessage() != null) {
            return e.awsErrorDetails().errorMessage() + " (HTTP " + e.statusCode() + ")";
        }
        return e.getMessage();
    }
}
