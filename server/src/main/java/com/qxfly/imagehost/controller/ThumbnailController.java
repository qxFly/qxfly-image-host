package com.qxfly.imagehost.controller;

import com.qxfly.imagehost.common.BizException;
import com.qxfly.imagehost.entity.ImageEntity;
import com.qxfly.imagehost.entity.StorageConfigEntity;
import com.qxfly.imagehost.mapper.ImageMapper;
import com.qxfly.imagehost.service.HotlinkGuard;
import com.qxfly.imagehost.service.ImageContentFetcher;
import com.qxfly.imagehost.service.StorageConfigService;
import com.qxfly.imagehost.service.ThumbnailService;
import com.qxfly.imagehost.storage.StorageSetting;
import com.qxfly.imagehost.storage.support.ObjectKeys;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

/**
 * 缩略图公开端点：{@code GET /thumb/{id}?w=400&s=签名}。
 * <p>
 * 匿名可访问（{@code <img>} 带不上 JWT），所以用 HMAC 签名防止按 id 枚举；
 * 内容不会变（原图内容指纹进了缓存文件名），可以长时间缓存。
 * 生成失败（SVG / 超大图）返回 404，前端会自动退回原图地址。
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class ThumbnailController {

    private final ThumbnailService thumbnailService;
    private final ImageMapper imageMapper;
    private final StorageConfigService storageConfigService;
    private final ImageContentFetcher contentFetcher;
    private final HotlinkGuard hotlinkGuard;

    @GetMapping("/thumb/{id}")
    public ResponseEntity<byte[]> thumb(@PathVariable Long id,
                                        @RequestParam(name = "w", required = false) Integer width,
                                        @RequestParam(name = "s", required = false) String signature,
                                        jakarta.servlet.http.HttpServletRequest request,
                                        HttpServletResponse response) {
        if (!hotlinkGuard.allowed(request)) {
            log.warn("防盗链拦截缩略图: referer={} id={}", request.getHeader("Referer"), id);
            return ResponseEntity.status(403).build();
        }
        int resolved = thumbnailService.normalizeWidth(width);
        try {
            thumbnailService.verify(id, resolved, signature);
        } catch (BizException e) {
            return ResponseEntity.status(403).build();
        }

        ImageEntity image = imageMapper.selectById(id);
        if (image == null || (image.getDeleted() != null && image.getDeleted() != 0)) {
            // 回收站里的图不给缩略图
            return ResponseEntity.notFound().build();
        }
        byte[] rendered;
        try {
            StorageConfigEntity config = storageConfigService.requireById(image.getStorageConfigId());
            StorageSetting setting = storageConfigService.toSetting(config);
            rendered = thumbnailService.render(id, resolved, image.getHash(), contentFetcher.fetch(image, setting));
        } catch (Exception e) {
            log.warn("生成缩略图失败 id={}: {}", id, e.getMessage());
            return ResponseEntity.notFound().build();
        }
        if (rendered == null || rendered.length == 0) {
            return ResponseEntity.notFound().build();
        }
        // 全局 encoding.force=true 会给响应补 charset，图片不需要
        response.setCharacterEncoding(null);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(thumbnailService.contentType(rendered)))
                .cacheControl(CacheControl.maxAge(Duration.ofDays(30)).cachePublic())
                .body(rendered);
    }
}
