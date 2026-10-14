package com.qxfly.imagehost.controller;

import com.qxfly.imagehost.common.ApiResult;
import com.qxfly.imagehost.dto.CdnDefaultsRequest;
import com.qxfly.imagehost.dto.CdnRequest;
import com.qxfly.imagehost.dto.CdnVO;
import com.qxfly.imagehost.dto.StorageConfigRequest;
import com.qxfly.imagehost.dto.StorageConfigVO;
import com.qxfly.imagehost.dto.StorageTypeVO;
import com.qxfly.imagehost.entity.StorageConfigEntity;
import com.qxfly.imagehost.security.SecurityUtils;
import com.qxfly.imagehost.service.ImageService;
import com.qxfly.imagehost.service.StorageCdnService;
import com.qxfly.imagehost.service.StorageConfigService;
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
 * 存储后端配置接口（含每个配置的 CDN 线路管理）。
 * <p>
 * 整个 {@code /api/storage-configs/**} 在 SecurityConfig 里限定为超级管理员，
 * 图片列表里的预览 / 下载 / 复制地址由服务端按这里的默认选择解析好后再返回。
 */
@RestController
@RequestMapping("/api/storage-configs")
@RequiredArgsConstructor
public class StorageConfigController {

    private final StorageConfigService storageConfigService;
    private final StorageCdnService storageCdnService;
    private final ImageService imageService;

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

    // ================================================================ CDN 线路

    /** 该存储配置的所有 CDN 线路：原始地址 + 内置预设 + 自定义，并标出各自是不是默认 */
    @GetMapping("/{id}/cdns")
    public ApiResult<List<CdnVO>> cdns(@PathVariable Long id) {
        return ApiResult.ok(storageCdnService.list(owned(id), sampleKey(id), sampleUrl(id)));
    }

    /** 新增自定义 CDN */
    @PostMapping("/{id}/cdns")
    public ApiResult<List<CdnVO>> addCdn(@PathVariable Long id, @Valid @RequestBody CdnRequest request) {
        return ApiResult.ok(storageCdnService.addCustom(owned(id), request, sampleKey(id), sampleUrl(id)));
    }

    /** 修改自定义 CDN（内置预设不可改） */
    @PutMapping("/{id}/cdns/{key}")
    public ApiResult<List<CdnVO>> updateCdn(@PathVariable Long id, @PathVariable String key,
                                            @Valid @RequestBody CdnRequest request) {
        return ApiResult.ok(storageCdnService.updateCustom(owned(id), key, request, sampleKey(id), sampleUrl(id)));
    }

    /** 删除自定义 CDN */
    @DeleteMapping("/{id}/cdns/{key}")
    public ApiResult<List<CdnVO>> deleteCdn(@PathVariable Long id, @PathVariable String key) {
        return ApiResult.ok(storageCdnService.deleteCustom(owned(id), key, sampleKey(id), sampleUrl(id)));
    }

    /** 设置预览 / 下载 / 复制各自的默认线路 */
    @PutMapping("/{id}/cdns/defaults")
    public ApiResult<List<CdnVO>> setCdnDefaults(@PathVariable Long id, @RequestBody CdnDefaultsRequest request) {
        return ApiResult.ok(storageCdnService.setDefaults(owned(id), request, sampleKey(id), sampleUrl(id)));
    }

    private StorageConfigEntity owned(Long id) {
        return storageConfigService.requireOwned(SecurityUtils.currentUserId(), id);
    }

    private String[] sample(Long id) {
        return imageService.sampleOf(id);
    }

    private String sampleKey(Long id) {
        String[] sample = sample(id);
        return sample == null ? null : sample[0];
    }

    private String sampleUrl(Long id) {
        String[] sample = sample(id);
        return sample == null ? null : sample[1];
    }
}
