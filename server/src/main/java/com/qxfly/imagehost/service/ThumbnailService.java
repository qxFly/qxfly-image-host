package com.qxfly.imagehost.service;

import com.qxfly.imagehost.common.BizException;
import com.qxfly.imagehost.config.AppProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 缩略图：按需缩放 + 磁盘缓存 + 带签名的公开直链。
 * <p>
 * 为什么是「按需」而不是上传时生成：图库里已有的几千张图不该为了缩略图重新跑一遍，
 * 而且原图可能在各家云上；按需生成第一次稍慢，之后直接命中磁盘缓存。
 * <p>
 * 为什么地址要签名：图库记录的 id 是连续自增的，缩略图端点又要匿名可访问
 * （{@code <img>} 带不上 JWT），不加签名就等于把全站图片暴露成可枚举的资源。
 * 签名是 HMAC(密钥, id:宽度)，稳定不变，所以浏览器可以长期缓存。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ThumbnailService {

    /** 缩略图公开路径前缀：/thumb/{id}?w=400&s=签名 */
    public static final String URL_PREFIX = "thumb";

    /** 每写这么多张检查一次缓存总量 */
    private static final int EVICT_CHECK_EVERY = 50;

    private final AppProperties appProperties;
    private final RequestBase requestBase;

    private final java.util.concurrent.atomic.AtomicInteger writesSinceEvict =
            new java.util.concurrent.atomic.AtomicInteger();

    /** 是否启用 */
    public boolean enabled() {
        return appProperties.getThumb().isEnabled();
    }

    public int defaultWidth() {
        return appProperties.getThumb().getDefaultWidth();
    }

    /** 给某张图生成缩略图地址；宽度不在允许区间时用默认宽度 */
    public String url(Long imageId, Integer width) {
        if (imageId == null || !enabled()) {
            return null;
        }
        int resolved = normalizeWidth(width);
        // 必须是绝对地址：页面可能在 Tauri / Vite 开发服务器 / 反向代理后面，根相对地址取不到
        String path = "/" + URL_PREFIX + "/" + imageId + "?w=" + resolved + "&s=" + sign(imageId, resolved);
        return requestBase.base() + path;
    }

    public int normalizeWidth(Integer width) {
        AppProperties.Thumb config = appProperties.getThumb();
        if (width == null) {
            return config.getDefaultWidth();
        }
        return Math.min(Math.max(width, config.getMinWidth()), config.getMaxWidth());
    }

    /** 校验签名，不合法直接拒绝 */
    public void verify(Long imageId, int width, String signature) {
        if (!StringUtils.hasText(signature) || !signature.equals(sign(imageId, width))) {
            throw BizException.forbidden("缩略图地址无效");
        }
    }

    /** HMAC-SHA256 前 32 位十六进制，稳定且不可伪造 */
    public String sign(Long imageId, int width) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(appProperties.getCryptoKey().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal((imageId + ":" + width).getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(32);
            for (int i = 0; i < 16; i++) {
                builder.append(Character.forDigit((digest[i] >> 4) & 0xF, 16));
                builder.append(Character.forDigit(digest[i] & 0xF, 16));
            }
            return builder.toString();
        } catch (Exception e) {
            throw new BizException("生成缩略图签名失败: " + e.getMessage());
        }
    }

    /**
     * 生成（或读取缓存）缩略图字节。
     *
     * @param sourceKeyHash 原图的内容指纹（image_file.hash），原图换了自动换缓存
     * @return JPEG/PNG 字节；无法处理（SVG、过大、解码失败）时返回 null，调用方退回原图地址
     */
    public byte[] render(Long imageId, int width, String sourceKeyHash, byte[] source) {
        AppProperties.Thumb config = appProperties.getThumb();
        if (source == null || source.length == 0 || source.length > config.getMaxSourceSize()) {
            return null;
        }
        Path cache = cacheFile(imageId, width, sourceKeyHash);
        try {
            if (Files.isRegularFile(cache)) {
                return Files.readAllBytes(cache);
            }
        } catch (IOException e) {
            log.debug("读缩略图缓存失败: {}", cache);
        }

        byte[] rendered = resize(source, width);
        if (rendered == null) {
            return null;
        }
        try {
            Files.createDirectories(cache.getParent());
            Path temp = Files.createTempFile(cache.getParent(), ".thumb-", ".tmp");
            Files.write(temp, rendered);
            Files.move(temp, cache, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            // 每写 50 张检查一次总量，避免缓存目录无限膨胀
            if (writesSinceEvict.incrementAndGet() >= 50) {
                writesSinceEvict.set(0);
                evictIfNeeded();
            }
        } catch (IOException e) {
            log.warn("写缩略图缓存失败: {}（{}）", cache, e.getMessage());
        }
        return rendered;
    }

    /** 缩略图的 contentType：带透明通道的用 png，其余用 jpeg */
    public String contentType(byte[] rendered) {
        return isPng(rendered) ? "image/png" : "image/jpeg";
    }

    private boolean isPng(byte[] bytes) {
        return bytes.length > 8 && (bytes[0] & 0xFF) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G';
    }

    private byte[] resize(byte[] source, int width) {
        try (InputStream in = new ByteArrayInputStream(source)) {
            BufferedImage image = ImageIO.read(in);
            if (image == null || image.getWidth() <= 0 || image.getHeight() <= 0) {
                // SVG / WebP 等 ImageIO 解不了的格式：不折腾，让前端用原图
                return null;
            }
            int targetWidth = Math.min(width, image.getWidth());
            int targetHeight = Math.max(1,
                    (int) Math.round(image.getHeight() * (targetWidth / (double) image.getWidth())));
            boolean alpha = image.getColorModel().hasAlpha();
            BufferedImage scaled = new BufferedImage(targetWidth, targetHeight,
                    alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = scaled.createGraphics();
            try {
                graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                        RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                graphics.drawImage(image, 0, 0, targetWidth, targetHeight, null);
            } finally {
                graphics.dispose();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (!ImageIO.write(scaled, alpha ? "png" : "jpg", out)) {
                return null;
            }
            return out.toByteArray();
        } catch (Exception e) {
            log.debug("生成缩略图失败: {}", e.getMessage());
            return null;
        }
    }

    /** 缓存目录统计（给设置页显示） */
    public java.util.Map<String, Object> cacheStats() {
        Path dir = cacheDir();
        long files = 0L;
        long bytes = 0L;
        if (Files.isDirectory(dir)) {
            try (var stream = Files.list(dir)) {
                for (Path path : stream.toList()) {
                    if (Files.isRegularFile(path)) {
                        files++;
                        bytes += Files.size(path);
                    }
                }
            } catch (IOException e) {
                log.debug("统计缩略图缓存失败: {}", e.getMessage());
            }
        }
        java.util.Map<String, Object> stats = new java.util.LinkedHashMap<>();
        stats.put("dir", dir.toString());
        stats.put("files", files);
        stats.put("bytes", bytes);
        stats.put("maxBytes", appProperties.getThumb().getCacheMaxSize());
        stats.put("days", appProperties.getThumb().getCacheDays());
        return stats;
    }

    /** 清空缩略图缓存，返回删除的文件数 */
    public int clearCache() {
        Path dir = cacheDir();
        int deleted = 0;
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        try (var stream = Files.list(dir)) {
            for (Path path : stream.toList()) {
                if (Files.isRegularFile(path) && Files.deleteIfExists(path)) {
                    deleted++;
                }
            }
        } catch (IOException e) {
            log.warn("清空缩略图缓存失败: {}", e.getMessage());
        }
        log.info("已清空缩略图缓存，删除 {} 个文件", deleted);
        return deleted;
    }

    /**
     * 缓存淘汰：先按天数删过期的，再按最久未使用（lastModified）删到总量上限以内。
     * 只在写入路径上惰性触发，不引入定时任务。
     */
    private void evictIfNeeded() {
        Path dir = cacheDir();
        if (!Files.isDirectory(dir)) {
            return;
        }
        AppProperties.Thumb config = appProperties.getThumb();
        long maxBytes = config.getCacheMaxSize();
        int days = config.getCacheDays();
        if (maxBytes <= 0 && days <= 0) {
            return;
        }
        try {
            java.util.List<Path> files = new java.util.ArrayList<>();
            long total = 0L;
            long deadline = System.currentTimeMillis() - days * 24L * 3600_000L;
            int expired = 0;
            try (var stream = Files.list(dir)) {
                for (Path path : stream.toList()) {
                    if (!Files.isRegularFile(path) || path.getFileName().toString().startsWith(".thumb-")) {
                        continue;
                    }
                    long modified = Files.getLastModifiedTime(path).toMillis();
                    if (days > 0 && modified < deadline) {
                        Files.deleteIfExists(path);
                        expired++;
                        continue;
                    }
                    files.add(path);
                    total += Files.size(path);
                }
            }
            int evicted = 0;
            if (maxBytes > 0 && total > maxBytes) {
                files.sort(java.util.Comparator.comparingLong(p -> {
                    try {
                        return Files.getLastModifiedTime(p).toMillis();
                    } catch (IOException e) {
                        return 0L;
                    }
                }));
                for (Path path : files) {
                    if (total <= maxBytes) {
                        break;
                    }
                    long size = Files.size(path);
                    if (Files.deleteIfExists(path)) {
                        total -= size;
                        evicted++;
                    }
                }
            }
            if (expired > 0 || evicted > 0) {
                log.info("缩略图缓存清理：过期 {} 个、超量淘汰 {} 个，剩余 {} 个", expired, evicted, files.size() - evicted);
            }
        } catch (IOException e) {
            log.warn("缩略图缓存清理失败: {}", e.getMessage());
        }
    }

    private Path cacheDir() {
        AppProperties.Thumb config = appProperties.getThumb();
        Path dir = StringUtils.hasText(config.getCacheDir())
                ? Paths.get(config.getCacheDir())
                : Paths.get(System.getProperty("java.io.tmpdir"), "qxfly-image-host-thumbs");
        if (!dir.isAbsolute()) {
            dir = Paths.get(System.getProperty("user.dir")).resolve(dir);
        }
        return dir.normalize();
    }

    private Path cacheFile(Long imageId, int width, String sourceHash) {
        String suffix = StringUtils.hasText(sourceHash) ? sourceHash.substring(0, Math.min(8, sourceHash.length())) : "nohash";
        return cacheDir().resolve(imageId + "-" + width + "-" + suffix + ".img");
    }
}
