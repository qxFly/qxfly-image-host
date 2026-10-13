package com.pichost.storage.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.pichost.common.BizException;
import com.pichost.storage.StorageObject;
import com.pichost.storage.StorageService;
import com.pichost.storage.StorageSetting;
import com.pichost.storage.StorageType;
import com.pichost.storage.UploadResult;
import com.pichost.storage.support.HttpSupport;
import com.pichost.storage.support.Json;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * SM.MS 免费图床（https://sm.ms）。
 * 匿名上传无需 Token，但无法删除；配置 Token 后可按 hash 删除。
 */
@Component
public class SmmsStorageService implements StorageService {

    private static final String API = "https://sm.ms/api/v2";

    @Override
    public StorageType type() {
        return StorageType.SMMS;
    }

    @Override
    public UploadResult upload(StorageSetting setting, StorageObject object) {
        HttpSupport.HttpResult result = HttpSupport.postMultipart(
                API + "/upload", headers(setting), Map.of(),
                "smfile", object.fileName(), object.contentType(), object.content());
        JsonNode node = Json.tree(result.body());

        if (node.path("success").asBoolean(false)) {
            JsonNode data = node.path("data");
            return new UploadResult(data.path("hash").asText(null), data.path("url").asText(null), null);
        }
        // 图片内容重复时 SM.MS 直接返回已有地址
        if ("image_repeated".equals(node.path("code").asText())) {
            return UploadResult.of(null, node.path("images").asText(null));
        }
        throw new BizException("上传到 SM.MS 失败: " + node.path("message").asText("HTTP " + result.status()));
    }

    @Override
    public void delete(StorageSetting setting, String objectKey) {
        if (objectKey == null || objectKey.isBlank()) {
            throw new BizException("缺少 SM.MS 图片 hash，无法删除（匿名上传的图片不支持删除）");
        }
        HttpSupport.HttpResult result = HttpSupport.get(API + "/delete/" + objectKey, headers(setting));
        JsonNode node = Json.tree(result.body());
        if (!node.path("success").asBoolean(false)) {
            throw new BizException("从 SM.MS 删除失败: " + node.path("message").asText("HTTP " + result.status()));
        }
    }

    @Override
    public void test(StorageSetting setting) {
        String token = setting.get("token");
        if (token == null) {
            // 匿名模式：没有可校验的凭证
            return;
        }
        HttpSupport.HttpResult result = HttpSupport.get(API + "/profile", headers(setting));
        JsonNode node = Json.tree(result.body());
        if (!node.path("success").asBoolean(false)) {
            throw new BizException("SM.MS Token 校验失败: " + node.path("message").asText("HTTP " + result.status()));
        }
    }

    private Map<String, String> headers(StorageSetting setting) {
        Map<String, String> headers = new LinkedHashMap<>();
        String token = setting.get("token");
        if (token != null) {
            headers.put("Authorization", token);
        }
        return headers;
    }
}
