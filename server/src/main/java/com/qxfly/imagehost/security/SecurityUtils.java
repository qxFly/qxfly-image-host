package com.qxfly.imagehost.security;

import com.qxfly.imagehost.common.BizException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 当前登录用户工具。
 */
public final class SecurityUtils {

    private SecurityUtils() {
    }

    public static AuthUser currentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof AuthUser user) {
            return user;
        }
        throw BizException.unauthorized("未登录或登录状态已失效");
    }

    public static Long currentUserId() {
        return currentUser().id();
    }

    /** 当前登录用户是不是超级管理员 */
    public static boolean isSuperAdmin() {
        return isSuperAdmin(currentUser());
    }

    public static boolean isSuperAdmin(AuthUser user) {
        return Role.of(user.role()).isSuperAdmin();
    }
}
