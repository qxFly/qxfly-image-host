package com.qxfly.imagehost.controller;

import com.qxfly.imagehost.common.ApiResult;
import com.qxfly.imagehost.dto.SettingsRequest;
import com.qxfly.imagehost.service.AppSettingService;
import com.qxfly.imagehost.service.ThumbnailService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 系统设置（仅超级管理员，见 SecurityConfig）。
 * <p>
 * 目前只有「单张文件大小上限」，支持设成无上限；改完立即生效，不用重启。
 */
@RestController
@RequestMapping("/api/settings")
@RequiredArgsConstructor
public class SettingsController {

    private final AppSettingService appSettingService;
    private final ThumbnailService thumbnailService;

    @GetMapping
    public ApiResult<Map<String, Object>> get() {
        return ApiResult.ok(appSettingService.snapshot());
    }

    /** 缩略图缓存占用（给设置页显示） */
    @GetMapping("/thumb-cache")
    public ApiResult<Map<String, Object>> thumbCache() {
        return ApiResult.ok(thumbnailService.cacheStats());
    }

    /** 清空缩略图缓存（只影响磁盘缓存，原图不受影响） */
    @DeleteMapping("/thumb-cache")
    public ApiResult<Integer> clearThumbCache() {
        return ApiResult.ok(thumbnailService.clearCache());
    }

    @PutMapping
    public ApiResult<Map<String, Object>> update(@RequestBody SettingsRequest request) {
        appSettingService.update(request);
        return ApiResult.ok(appSettingService.snapshot());
    }
}
