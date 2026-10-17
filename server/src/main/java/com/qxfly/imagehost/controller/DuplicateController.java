package com.qxfly.imagehost.controller;

import com.qxfly.imagehost.common.ApiResult;
import com.qxfly.imagehost.security.SecurityUtils;
import com.qxfly.imagehost.service.DuplicateService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 重复图片查找与清理（仅超级管理员，见 SecurityConfig）。
 */
@RestController
@RequestMapping("/api/images/duplicates")
@RequiredArgsConstructor
public class DuplicateController {

    private final DuplicateService duplicateService;

    @GetMapping
    public ApiResult<Map<String, Object>> groups(@RequestParam(defaultValue = "1") long page,
                                                 @RequestParam(defaultValue = "20") long size,
                                                 @RequestParam(required = false) Long storageConfigId,
                                                 @RequestParam(required = false) String directory) {
        return ApiResult.ok(duplicateService.groups(SecurityUtils.currentUser(), page, size, storageConfigId, directory));
    }

    /**
     * 清理：每组只保留一张，其余移入回收站。
     *
     * @param keep 保留策略：earliest（最早，默认）/ latest（最新）/ largest（最大）
     */
    @DeleteMapping
    public ApiResult<Integer> cleanup(@RequestParam(required = false) String hash,
                                      @RequestParam(defaultValue = "earliest") String keep,
                                      @RequestParam(required = false) Long storageConfigId) {
        return ApiResult.ok(duplicateService.cleanup(SecurityUtils.currentUser(), hash, keep, storageConfigId));
    }
}
