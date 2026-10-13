package com.pichost.controller;

import com.pichost.common.ApiResult;
import com.pichost.config.AppProperties;
import com.pichost.storage.StorageServiceFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 系统信息：前端用于展示可用存储类型与上传限制。
 */
@RestController
@RequestMapping("/api/system")
@RequiredArgsConstructor
public class SystemController {

    private final StorageServiceFactory storageServiceFactory;
    private final AppProperties appProperties;

    @GetMapping("/info")
    public ApiResult<Map<String, Object>> info() {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("name", "PicHost");
        info.put("version", "0.1.0");
        info.put("storageTypes", storageServiceFactory.supportedTypes().stream().map(Enum::name).toList());
        info.put("maxUploadSize", appProperties.getUpload().getMaxSize());
        info.put("allowedExtensions", appProperties.getUpload().getAllowedExtensions());
        info.put("dedupeEnabled", appProperties.isDedupeEnabled());
        info.put("pathShardSize", appProperties.getUpload().getShardSize());
        info.put("importMaxFiles", appProperties.getUpload().getImportMaxFiles());
        info.put("migrateConcurrency", appProperties.getMigrate().getConcurrency());
        return ApiResult.ok(info);
    }

    @GetMapping("/storage-types")
    public ApiResult<List<String>> storageTypes() {
        return ApiResult.ok(storageServiceFactory.supportedTypes().stream().map(Enum::name).toList());
    }
}
