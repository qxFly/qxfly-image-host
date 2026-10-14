package com.qxfly.imagehost.controller;

import com.qxfly.imagehost.common.ApiResult;
import com.qxfly.imagehost.config.AppProperties;
import com.qxfly.imagehost.dto.LoginRequest;
import com.qxfly.imagehost.dto.LoginResponse;
import com.qxfly.imagehost.dto.PasswordRequest;
import com.qxfly.imagehost.dto.ProfileRequest;
import com.qxfly.imagehost.dto.RegisterRequest;
import com.qxfly.imagehost.dto.UserVO;
import com.qxfly.imagehost.security.SecurityUtils;
import com.qxfly.imagehost.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 认证接口。
 * <p>
 * 注册默认是关闭的（账号由超级管理员创建），只有 {@code app.register.enabled=true} 时才允许自助注册，
 * 注册出来的账号一律是普通用户。
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final UserService userService;
    private final AppProperties appProperties;

    @PostMapping("/login")
    public ApiResult<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        return ApiResult.ok(userService.login(request));
    }

    /** 注册接口：默认仅超级管理员可用；打开自助注册后匿名可调用（权限在 SecurityConfig 里限定） */
    @PostMapping("/register")
    public ApiResult<UserVO> register(@Valid @RequestBody RegisterRequest request) {
        return ApiResult.ok(userService.register(request));
    }

    /** 登录页据此决定要不要显示「注册」入口（匿名可访问） */
    @GetMapping("/register-enabled")
    public ApiResult<Map<String, Boolean>> registerEnabled() {
        return ApiResult.ok(Map.of("enabled", appProperties.getRegister().isEnabled()));
    }

    @GetMapping("/me")
    public ApiResult<UserVO> me() {
        return ApiResult.ok(userService.getById(SecurityUtils.currentUserId()));
    }

    @PutMapping("/profile")
    public ApiResult<UserVO> updateProfile(@Valid @RequestBody ProfileRequest request) {
        return ApiResult.ok(userService.updateProfile(SecurityUtils.currentUserId(), request));
    }

    @PutMapping("/password")
    public ApiResult<Void> changePassword(@Valid @RequestBody PasswordRequest request) {
        userService.changePassword(SecurityUtils.currentUserId(), request);
        return ApiResult.ok();
    }
}
