package com.qxfly.imagehost.controller;

import com.qxfly.imagehost.common.ApiResult;
import com.qxfly.imagehost.dto.SettingsRequest;
import com.qxfly.imagehost.service.AppSettingService;
import lombok.RequiredArgsConstructor;
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

    @GetMapping
    public ApiResult<Map<String, Object>> get() {
        return ApiResult.ok(appSettingService.snapshot());
    }

    @PutMapping
    public ApiResult<Map<String, Object>> update(@RequestBody SettingsRequest request) {
        appSettingService.update(request);
        return ApiResult.ok(appSettingService.snapshot());
    }
}
