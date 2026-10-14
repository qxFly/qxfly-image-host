package com.qxfly.imagehost.controller;

import com.qxfly.imagehost.common.ApiResult;
import com.qxfly.imagehost.common.PageResult;
import com.qxfly.imagehost.dto.UserCreateRequest;
import com.qxfly.imagehost.dto.UserResetPasswordRequest;
import com.qxfly.imagehost.dto.UserStatusRequest;
import com.qxfly.imagehost.dto.UserUpdateRequest;
import com.qxfly.imagehost.dto.UserVO;
import com.qxfly.imagehost.security.SecurityUtils;
import com.qxfly.imagehost.service.UserService;
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

/**
 * 账号管理接口。
 * <p>
 * 整个 {@code /api/users/**} 在 SecurityConfig 里已经限定为「仅超级管理员」。
 * 这里<b>没有修改角色的接口</b>：新建的账号固定是普通用户，
 * 超级管理员只能通过数据库赋予（详见 {@link com.qxfly.imagehost.security.Role}）。
 */
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    /** 账号列表 */
    @GetMapping
    public ApiResult<PageResult<UserVO>> page(@RequestParam(defaultValue = "1") long page,
                                              @RequestParam(defaultValue = "20") long size,
                                              @RequestParam(required = false) String keyword) {
        return ApiResult.ok(userService.page(keyword, page, size));
    }

    /** 新建普通用户账号 */
    @PostMapping
    public ApiResult<UserVO> create(@Valid @RequestBody UserCreateRequest request) {
        return ApiResult.ok(userService.createUser(request));
    }

    /** 修改账号资料（昵称 / 邮箱），仅超级管理员 */
    @PutMapping("/{id}")
    public ApiResult<UserVO> update(@PathVariable Long id, @Valid @RequestBody UserUpdateRequest request) {
        return ApiResult.ok(userService.updateUser(id, request));
    }

    /** 启用 / 禁用账号 */
    @PutMapping("/{id}/status")
    public ApiResult<UserVO> updateStatus(@PathVariable Long id,
                                          @Valid @RequestBody UserStatusRequest request) {
        return ApiResult.ok(userService.updateStatus(SecurityUtils.currentUserId(), id, request.getStatus()));
    }

    /** 重置某个账号的密码 */
    @PutMapping("/{id}/password")
    public ApiResult<Void> resetPassword(@PathVariable Long id,
                                         @Valid @RequestBody UserResetPasswordRequest request) {
        userService.resetPassword(id, request.getNewPassword());
        return ApiResult.ok();
    }

    /** 删除账号（名下还有图片时会被拒绝） */
    @DeleteMapping("/{id}")
    public ApiResult<Void> delete(@PathVariable Long id) {
        userService.deleteUser(SecurityUtils.currentUserId(), id);
        return ApiResult.ok();
    }
}
