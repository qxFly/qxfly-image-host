package com.pichost.security;

/**
 * 认证主体（登录后放入 SecurityContext）。
 */
public record AuthUser(Long id, String username, String role) {
}
