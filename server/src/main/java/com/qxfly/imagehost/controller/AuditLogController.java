package com.qxfly.imagehost.controller;

import com.qxfly.imagehost.common.ApiResult;
import com.qxfly.imagehost.common.PageResult;
import com.qxfly.imagehost.entity.AuditLogEntity;
import com.qxfly.imagehost.service.AuditService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 审计日志查询（仅超级管理员，见 SecurityConfig）。
 */
@RestController
@RequestMapping("/api/audit-logs")
@RequiredArgsConstructor
public class AuditLogController {

    private final AuditService auditService;

    @GetMapping
    public ApiResult<PageResult<AuditLogEntity>> page(@RequestParam(defaultValue = "1") long page,
                                                      @RequestParam(defaultValue = "50") long size,
                                                      @RequestParam(required = false) String keyword,
                                                      @RequestParam(required = false) String action,
                                                      @RequestParam(required = false) Boolean success) {
        return ApiResult.ok(auditService.page(page, size, keyword, action, success));
    }
}
