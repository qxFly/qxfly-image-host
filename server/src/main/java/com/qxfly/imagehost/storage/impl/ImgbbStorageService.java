package com.qxfly.imagehost.storage.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.qxfly.imagehost.common.BizException;
import com.qxfly.imagehost.storage.StorageObject;
import com.qxfly.imagehost.storage.StorageService;
import com.qxfly.imagehost.storage.StorageSetting;
import com.qxfly.imagehost.storage.StorageType;
import com.qxfly.imagehost.storage.UploadResult;
import com.qxfly.imagehost.storage.support.HttpSupport;
import com.qxfly.imagehost.storage.support.Json;
import com.qxfly.imagehost.storage.support.ObjectKeys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ImgBB 免费图床（https://api.imgbb.com）。
 * 官方公开 API 未提供删除接口，这里的 objectKey 保存 delete_url 以便人工处理。
 */
@Slf4j
@Component
public class ImgbbStorageService implements StorageService {

    private static final String API = "https://api.imgbb.com/1/upload";

    @Override
    public StorageType type() {
        return StorageType.IMGBB;
    }

    @Override
    public UploadResult upload(StorageSetting setting, StorageObject object) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("image", Base64.getEncoder().encodeToString(object.content()));
        form.put("name", ObjectKeys.fileName(object.objectKey()));
        int expiration = setting.getInt("expiration", 0);
        if (expiration > 0) {
            form.put("expiration", String.valueOf(expiration));
        }

        HttpSupport.HttpResult result = HttpSupport.postForm(uploadUrl(setting), Map.of(), form);
        JsonNode node = Json.tree(result.body());
        if (!result.isOk() || !node.path("success").asBoolean(false)) {
            throw new BizException("上传到 ImgBB 失败: "
                    + node.path("error").path("message").asText("HTTP " + result.status()));
        }
        JsonNode data = node.path("data");
        String url = data.path("display_url").asText(data.path("url").asText(null));
        String thumbUrl = data.path("thumb").path("url").asText(null);
        return new UploadResult(data.path("delete_url").asText(null), url, thumbUrl);
    }

    @Override
    public void delete(StorageSetting setting, String objectKey) {
        log.warn("ImgBB 公开 API 不支持程序化删除，请打开以下地址人工删除: {}", objectKey);
    }

    @Override
    public boolean supportsDelete() {
        return false;
    }

    @Override
    public void test(StorageSetting setting) {
        // 不带图片请求上传接口：Key 合法时返回 “Empty image”，非法时返回 “Invalid API v1 key”
        HttpSupport.HttpResult result = HttpSupport.postForm(uploadUrl(setting), Map.of(), Map.of());
        String body = result.body() == null ? "" : result.body();
        if (body.contains("Invalid API")) {
            throw new BizException("ImgBB API Key 无效");
        }
    }

    private String uploadUrl(StorageSetting setting) {
        return API + "?key=" + HttpSupport.encode(setting.get("apiKey"));
    }
}
