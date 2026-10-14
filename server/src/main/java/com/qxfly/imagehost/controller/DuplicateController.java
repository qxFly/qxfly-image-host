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
                                                 @RequestParam(defaultValue = "20") long size) {
        return ApiResult.ok(duplicateService.groups(SecurityUtils.currentUser(), page, size));
    }

    /** 清理：每组保留最早的一张，其余移入回收站；带 hash 只清理这一组 */
    @DeleteMapping
    public ApiResult<Integer> cleanup(@RequestParam(required = false) String hash) {
        return ApiResult.ok(duplicateService.cleanup(SecurityUtils.currentUser(), hash));
    }
}
