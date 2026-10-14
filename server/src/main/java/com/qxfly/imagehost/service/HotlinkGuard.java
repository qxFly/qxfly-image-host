package com.qxfly.imagehost.service;

import com.qxfly.imagehost.config.AppProperties;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.util.List;

/**
 * 防盗链。
 * <p>
 * 只对「本服务自己提供文件」的公开端点有意义（本地存储的 /files/**、缩略图 /thumb/**）；
 * GitHub / OSS 那些后端的直链防盗链要在各自的控制台或 CDN 上配。
 * <p>
 * 默认关闭 —— 打开后如果白名单没配全，别人正常引用你的图会被 403，风险比收益大，
 * 所以交给用户在「系统设置」里显式打开。
 * 没有 Referer 的请求默认放行：直接粘贴地址、浏览器隐私设置都会没有 Referer，
 * 一刀切会误伤正常访客。
 */
@Component
@RequiredArgsConstructor
public class HotlinkGuard {

    private final AppSettingService appSettingService;
    private final AppProperties appProperties;

    /** 是否允许这次访问 */
    public boolean allowed(HttpServletRequest request) {
        if (!appSettingService.hotlinkEnabled()) {
            return true;
        }
        String host = hostOf(request.getHeader("Referer"));
        if (host == null) {
            return appSettingService.hotlinkAllowEmpty();
        }
        // 同源（画廊页面自己引用）永远放行
        if (host.equalsIgnoreCase(request.getServerName())) {
            return true;
        }
        List<String> domains = appSettingService.hotlinkDomains();
        for (String domain : domains) {
            if (host.equalsIgnoreCase(domain) || host.endsWith("." + domain)) {
                return true;
            }
        }
        return false;
    }

    private String hostOf(String referer) {
        if (!StringUtils.hasText(referer)) {
            return null;
        }
        try {
            String host = URI.create(referer.trim()).getHost();
            return StringUtils.hasText(host) ? host.toLowerCase() : null;
        } catch (Exception e) {
            return null;
        }
    }
}
