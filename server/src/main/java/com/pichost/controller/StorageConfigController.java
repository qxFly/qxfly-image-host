package com.pichost.controller;

import com.pichost.common.ApiResult;
import com.pichost.dto.StorageConfigRequest;
import com.pichost.dto.StorageConfigVO;
import com.pichost.dto.StorageTypeVO;
import com.pichost.security.SecurityUtils;
import com.pichost.service.StorageConfigService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 存储后端配置接口。
 */
@RestController
@RequestMapping("/api/storage-configs")
@RequiredArgsConstructor
public class StorageConfigController {

    private final StorageConfigService storageConfigService;

    /** 支持的存储类型及表单字段定义 */
    @GetMapping("/types")
    public ApiResult<List<StorageTypeVO>> types() {
        return ApiResult.ok(storageConfigService.types());
    }

    @GetMapping
    public ApiResult<List<StorageConfigVO>> list() {
        return ApiResult.ok(storageConfigService.list(SecurityUtils.currentUserId()));
    }

    @PostMapping
    public ApiResult<StorageConfigVO> create(@Valid @RequestBody StorageConfigRequest request) {
        return ApiResult.ok(storageConfigService.create(SecurityUtils.currentUserId(), request));
    }

    @PutMapping("/{id}")
    public ApiResult<StorageConfigVO> update(@PathVariable Long id, @Valid @RequestBody StorageConfigRequest request) {
        return ApiResult.ok(storageConfigService.update(SecurityUtils.currentUserId(), id, request));
    }

    @DeleteMapping("/{id}")
    public ApiResult<Void> delete(@PathVariable Long id) {
        storageConfigService.delete(SecurityUtils.currentUserId(), id);
        return ApiResult.ok();
    }

    @PutMapping("/{id}/default")
    public ApiResult<StorageConfigVO> setDefault(@PathVariable Long id) {
        return ApiResult.ok(storageConfigService.setDefault(SecurityUtils.currentUserId(), id));
    }

    /** 测试已保存的配置 */
    @PostMapping("/{id}/test")
    public ApiResult<Map<String, String>> test(@PathVariable Long id) {
        String message = storageConfigService.test(SecurityUtils.currentUserId(), id);
        return ApiResult.ok(Map.of("message", message));
    }

    /** 测试尚未保存的配置；existingId 用于复用已保存的敏感字段 */
    @PostMapping("/test")
    public ApiResult<Map<String, String>> testUnsaved(@RequestBody StorageConfigRequest request,
                                                      @RequestParam(required = false) Long existingId) {
        String message = storageConfigService.test(SecurityUtils.currentUserId(), existingId, request);
        return ApiResult.ok(Map.of("message", message));
    }
}
