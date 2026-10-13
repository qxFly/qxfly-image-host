package com.pichost.controller;

import com.pichost.common.ApiResult;
import com.pichost.dto.LoginRequest;
import com.pichost.dto.LoginResponse;
import com.pichost.dto.PasswordRequest;
import com.pichost.dto.ProfileRequest;
import com.pichost.dto.RegisterRequest;
import com.pichost.dto.UserVO;
import com.pichost.security.SecurityUtils;
import com.pichost.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证接口。
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final UserService userService;

    @PostMapping("/login")
    public ApiResult<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        return ApiResult.ok(userService.login(request));
    }

    @PostMapping("/register")
    public ApiResult<UserVO> register(@Valid @RequestBody RegisterRequest request) {
        return ApiResult.ok(userService.register(request));
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
