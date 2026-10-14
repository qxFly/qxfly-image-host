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

    private final AppProperties appProperties;
    private final RequestBase requestBase;

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

    private Path cacheFile(Long imageId, int width, String sourceHash) {
        AppProperties.Thumb config = appProperties.getThumb();
        Path dir = StringUtils.hasText(config.getCacheDir())
                ? Paths.get(config.getCacheDir())
                : Paths.get(System.getProperty("java.io.tmpdir"), "qxfly-image-host-thumbs");
        if (!dir.isAbsolute()) {
            dir = Paths.get(System.getProperty("user.dir")).resolve(dir);
        }
        String suffix = StringUtils.hasText(sourceHash) ? sourceHash.substring(0, Math.min(8, sourceHash.length())) : "nohash";
        return dir.normalize().resolve(imageId + "-" + width + "-" + suffix + ".img");
    }
}
