package com.qxfly.imagehost.service;

import com.qxfly.imagehost.config.AppProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 缩略图：签名、宽度夹取、缩放、缓存、异常格式回退。
 */
class ThumbnailServiceTest {

    @TempDir
    Path cacheDir;

    private ThumbnailService service;
    private RequestBase requestBase;

    @BeforeEach
    void setUp() {
        AppProperties properties = new AppProperties();
        properties.getThumb().setCacheDir(cacheDir.toString());
        properties.getThumb().setDefaultWidth(400);
        requestBase = new RequestBase(properties);
        // 单元测试没有 Spring 容器，直接塞端口；没有请求上下文时 base() 会退回本机回环
        ReflectionTestUtils.setField(requestBase, "serverPort", 8080);
        service = new ThumbnailService(properties, requestBase);
    }

    private byte[] image(int width, int height, boolean alpha, String format) throws IOException {
        BufferedImage image = new BufferedImage(width, height, alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(Color.RED);
        graphics.fillRect(0, 0, width, height);
        graphics.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, format, out);
        return out.toByteArray();
    }

    private BufferedImage decode(byte[] bytes) throws IOException {
        return ImageIO.read(new ByteArrayInputStream(bytes));
    }

    @Test
    void 签名稳定且随ID和宽度变化() {
        String first = service.sign(12L, 400);
        assertThat(first).hasSize(32);
        assertThat(service.sign(12L, 400)).isEqualTo(first);
        assertThat(service.sign(13L, 400)).isNotEqualTo(first);
        assertThat(service.sign(12L, 200)).isNotEqualTo(first);
    }

    @Test
    void 宽度会被夹到允许区间() {
        assertThat(service.normalizeWidth(null)).isEqualTo(400);
        assertThat(service.normalizeWidth(10)).isEqualTo(32);
        assertThat(service.normalizeWidth(999999)).isEqualTo(1600);
        assertThat(service.normalizeWidth(600)).isEqualTo(600);
    }

    @Test
    void 地址带签名且能通过校验() {
        String url = service.url(7L, 200);
        // 必须是绝对地址：页面可能不在后端这个来源上（Tauri / Vite / 反代）
        assertThat(url).startsWith("http://127.0.0.1:8080/thumb/7?w=200&s=");
        String signature = url.substring(url.indexOf("&s=") + 3);
        service.verify(7L, 200, signature);
        assertThatThrownBy(() -> service.verify(7L, 200, "bad-signature"))
                .hasMessageContaining("缩略图地址无效");
        assertThatThrownBy(() -> service.verify(8L, 200, signature))
                .hasMessageContaining("缩略图地址无效");
    }

    @Test
    void 缩放到指定宽度并写入缓存() throws IOException {
        byte[] source = image(200, 100, false, "png");
        byte[] thumb = service.render(5L, 80, "hash1234", source);

        assertThat(thumb).isNotNull();
        BufferedImage decoded = decode(thumb);
        assertThat(decoded.getWidth()).isEqualTo(80);
        assertThat(decoded.getHeight()).isEqualTo(40);
        assertThat(service.contentType(thumb)).isEqualTo("image/jpeg");

        // 缓存文件已写出，第二次直接命中缓存（同样的字节）
        assertThat(Files.list(cacheDir).count()).isEqualTo(1);
        assertThat(service.render(5L, 80, "hash1234", source)).isEqualTo(thumb);
        // 原图指纹变了 -> 换缓存
        assertThat(service.render(5L, 80, "other999", source)).isNotNull();
        assertThat(Files.list(cacheDir).count()).isEqualTo(2);
    }

    @Test
    void 带透明通道的图输出PNG() throws IOException {
        byte[] thumb = service.render(6L, 40, null, image(100, 100, true, "png"));
        assertThat(thumb).isNotNull();
        assertThat(service.contentType(thumb)).isEqualTo("image/png");
    }

    @Test
    void 原图比目标还小时不放大() throws IOException {
        byte[] thumb = service.render(7L, 400, null, image(30, 20, false, "png"));
        assertThat(thumb).isNotNull();
        assertThat(decode(thumb).getWidth()).isEqualTo(30);
    }

    @Test
    void 解不了的格式与超大原图返回null() throws IOException {
        // SVG 这类 ImageIO 读不了的：返回 null，让前端用原图
        assertThat(service.render(8L, 400, null, "<svg></svg>".getBytes())).isNull();

        // 超过 maxSourceSize 的也不处理
        AppProperties small = new AppProperties();
        small.getThumb().setCacheDir(cacheDir.toString());
        small.getThumb().setMaxSourceSize(10);
        ThumbnailService limited = new ThumbnailService(small, requestBase);
        assertThat(limited.render(9L, 400, null, image(100, 100, false, "png"))).isNull();
    }
}
