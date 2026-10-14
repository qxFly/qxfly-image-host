package com.qxfly.imagehost.controller;

import com.qxfly.imagehost.common.ApiResult;
import com.qxfly.imagehost.dto.ShareRequest;
import com.qxfly.imagehost.dto.ShareVO;
import com.qxfly.imagehost.security.SecurityUtils;
import com.qxfly.imagehost.service.ShareService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 分享链接（相册）。
 * <p>
 * 管理接口（建 / 列表 / 撤销）仅超级管理员；
 * {@code POST /api/shares/{token}/view} 是**公开**的，匿名就能打开相册（见 SecurityConfig），
 * 需要密码的相册在这里校验密码。
 */
@RestController
@RequestMapping("/api/shares")
@RequiredArgsConstructor
public class ShareController {

    private final ShareService shareService;

    @PostMapping
    public ApiResult<ShareVO> create(@Valid @RequestBody ShareRequest request) {
        return ApiResult.ok(shareService.create(SecurityUtils.currentUserId(), request));
    }

    @GetMapping
    public ApiResult<List<ShareVO>> list() {
        return ApiResult.ok(shareService.list(SecurityUtils.currentUserId()));
    }

    @DeleteMapping("/{id}")
    public ApiResult<Void> delete(@PathVariable Long id) {
        shareService.delete(SecurityUtils.currentUserId(), id);
        return ApiResult.ok();
    }

    /** 公开访问：带密码时把密码放在请求体里 */
    @PostMapping("/{token}/view")
    public ApiResult<ShareVO> view(@PathVariable String token,
                                   @RequestBody(required = false) Map<String, String> body) {
        String password = body == null ? null : body.get("password");
        return ApiResult.ok(shareService.view(token, password));
    }
}
