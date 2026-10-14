package com.qxfly.imagehost.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * 「本服务对外是什么地址」的统一判断。
 * <p>
 * 生成缩略图、本地存储直链这类**要交给浏览器去取**的地址时，必须是绝对地址：
 * 页面来源不一定是后端自己 —— Tauri 桌面端页面来自 {@code tauri://localhost}、
 * Vite 开发服务器只代理 /api、反向代理挂在别的域名下，根相对地址（{@code /thumb/...}）都会打不开。
 * <p>
 * 取值顺序：当前请求的 scheme://host:port（最准，含反向代理后的地址）→
 * {@code app.public-base-url} 配置 → {@code http://127.0.0.1:<server.port>}。
 */
@Component
public class RequestBase {

    @Value("${server.port:8080}")
    private int serverPort;

    private final com.qxfly.imagehost.config.AppProperties appProperties;

    public RequestBase(com.qxfly.imagehost.config.AppProperties appProperties) {
        this.appProperties = appProperties;
    }

    /** 当前请求的基地址；后台线程（没有请求上下文）返回 null */
    public String currentBase() {
        try {
            String base = ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString();
            return StringUtils.hasText(base) ? stripTrailingSlash(base) : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 可用的基地址：请求 → 配置 → 本机回环 */
    public String base() {
        String current = currentBase();
        if (StringUtils.hasText(current)) {
            return current;
        }
        String configured = appProperties.getPublicBaseUrl();
        if (StringUtils.hasText(configured)) {
            return stripTrailingSlash(configured.trim());
        }
        return "http://127.0.0.1:" + serverPort;
    }

    private String stripTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
