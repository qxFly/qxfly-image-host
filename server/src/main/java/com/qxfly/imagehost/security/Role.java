package com.qxfly.imagehost.security;

import java.util.Locale;

/**
 * 系统角色，只有两种。
 * <p>
 * <b>超级管理员（SUPER_ADMIN）</b>：可完整操作系统 —— 上传、删除、迁移、导入、存储配置、账号管理。
 * <b>普通用户（USER）</b>：只能浏览、查看与下载。
 * <p>
 * <b>超级管理员只能通过数据库赋予</b>：注册接口和「用户管理」创建出来的账号一律是 {@link #USER}，
 * 系统里也没有任何修改角色的接口。要提升角色请直接改库：
 * <pre>UPDATE sys_user SET role = 'SUPER_ADMIN' WHERE username = 'xxx';</pre>
 * 改完立即生效（每次请求都会回库读角色），不需要重新登录。
 */
public enum Role {

    SUPER_ADMIN("超级管理员", "可完整操作系统"),
    USER("普通用户", "仅能浏览、查看与下载");

    private final String label;
    private final String description;

    Role(String label, String description) {
        this.label = label;
        this.description = description;
    }

    public String getLabel() {
        return label;
    }

    public String getDescription() {
        return description;
    }

    /** Spring Security 用的权限名，例如 ROLE_SUPER_ADMIN */
    public String authority() {
        return "ROLE_" + name();
    }

    public boolean isSuperAdmin() {
        return this == SUPER_ADMIN;
    }

    /**
     * 把库里存的字符串转成角色。
     * <p>
     * 空值、认不出来的值都按「普通用户」处理（宁可少给权限，也不能因为脏数据意外提权）；
     * 但 {@code ADMIN} 是旧版本的超级管理员，必须继续认，否则老库里的 admin 会直接失权。
     */
    public static Role of(String value) {
        if (value == null || value.isBlank()) {
            return USER;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if ("ADMIN".equals(normalized)) {
            return SUPER_ADMIN;
        }
        try {
            return valueOf(normalized);
        } catch (IllegalArgumentException e) {
            return USER;
        }
    }
}
