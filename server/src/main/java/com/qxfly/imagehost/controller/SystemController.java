package com.qxfly.imagehost.controller;

import com.qxfly.imagehost.common.ApiResult;
import com.qxfly.imagehost.config.AppProperties;
import com.qxfly.imagehost.service.AppSettingService;
import com.qxfly.imagehost.storage.StorageServiceFactory;
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
    private final AppSettingService appSettingService;

    @GetMapping("/info")
    public ApiResult<Map<String, Object>> info() {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("name", "qxfly-image-host");
        info.put("version", "0.1.0");
        info.put("storageTypes", storageServiceFactory.supportedTypes().stream().map(Enum::name).toList());
        // 单张大小上限是运行时设置（可在「系统设置」里改成无上限），-1 表示无上限
        info.put("maxUploadSize", appSettingService.maxUploadSize());
        info.put("maxUploadSizeText", appSettingService.describeLimit());
        info.put("unlimitedUploadSize", appSettingService.unlimited());
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
