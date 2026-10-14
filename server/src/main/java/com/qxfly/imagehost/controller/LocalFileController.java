package com.qxfly.imagehost.controller;

import com.qxfly.imagehost.common.BizException;
import com.qxfly.imagehost.entity.StorageConfigEntity;
import com.qxfly.imagehost.service.HotlinkGuard;
import com.qxfly.imagehost.service.StorageConfigService;
import com.qxfly.imagehost.storage.StorageSetting;
import com.qxfly.imagehost.storage.StorageType;
import com.qxfly.imagehost.storage.impl.LocalStorageService;
import com.qxfly.imagehost.storage.support.ImageTypes;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;

/**
 * 本地存储的公开访问端点：{@code GET /files/{配置ID}/{对象键}}。
 * <p>
 * 图片直链就是这个地址（保存在 image_file.url 里），所以它必须匿名可访问 ——
 * 浏览器的 {@code <img src>} 带不上 JWT。这是「公开图床」的固有语义，
 * 其他后端（GitHub raw / jsDelivr / OSS ...）的直链同样是公开的；
 * 对象键里含随机 uuid，无法被猜到。
 * <p>
 * 路径不在 /api 下，SecurityConfig 里 {@code anyRequest().permitAll()} 放行。
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class LocalFileController {

    private static final String PATH_PREFIX = "/files/";

    private final StorageConfigService storageConfigService;
    private final LocalStorageService localStorageService;
    private final HotlinkGuard hotlinkGuard;

    @GetMapping("/files/{configId}/**")
    public ResponseEntity<Resource> file(@PathVariable Long configId,
                                        HttpServletRequest request,
                                        HttpServletResponse response) {
        if (!hotlinkGuard.allowed(request)) {
            log.warn("防盗链拦截: referer={} key={}", request.getHeader("Referer"), request.getRequestURI());
            return ResponseEntity.status(403).build();
        }
        String uri = request.getRequestURI();
        String prefix = PATH_PREFIX + configId + "/";
        if (!uri.startsWith(prefix)) {
            return ResponseEntity.notFound().build();
        }
        String objectKey;
        try {
            objectKey = URLDecoder.decode(uri.substring(prefix.length()), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }

        StorageSetting setting;
        try {
            StorageConfigEntity config = storageConfigService.requireById(configId);
            if (StorageType.of(config.getType()) != StorageType.LOCAL) {
                // 只有本地存储的文件由本服务提供，其他后端有自己的直链
                return ResponseEntity.notFound().build();
            }
            setting = storageConfigService.toSetting(config);
        } catch (BizException e) {
            // 业务异常默认是 HTTP 200 + 错误码，但这是一个静态资源端点，
            // 直接回 404 更符合浏览器/爬虫的预期（也避免把 JSON 当图片渲染）
            return ResponseEntity.notFound().build();
        }

        Path path;
        try {
            path = localStorageService.locateExisting(setting, objectKey);
        } catch (BizException e) {
            log.warn("本地文件访问被拒绝: {}", e.getMessage());
            return ResponseEntity.notFound().build();
        }
        if (path == null) {
            return ResponseEntity.notFound().build();
        }
        // 全局配置 server.servlet.encoding.force=true 会给所有响应补上 charset，
        // 图片是二进制，带上 ;charset=UTF-8 没有意义，这里清掉
        response.setCharacterEncoding(null);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(ImageTypes.mimeOf(objectKey)))
                // 对象键含 uuid、内容不会变，可以放心让浏览器长时间缓存
                .cacheControl(CacheControl.maxAge(Duration.ofDays(7)).cachePublic())
                .body(new FileSystemResource(path));
    }
}
