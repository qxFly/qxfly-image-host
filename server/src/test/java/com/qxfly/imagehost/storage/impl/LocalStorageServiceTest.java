package com.qxfly.imagehost.storage.impl;

import com.qxfly.imagehost.common.BizException;
import com.qxfly.imagehost.config.AppProperties;
import com.qxfly.imagehost.storage.StorageObject;
import com.qxfly.imagehost.storage.StorageSetting;
import com.qxfly.imagehost.storage.StorageType;
import com.qxfly.imagehost.storage.UploadResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 本地存储：磁盘读写、搬移（回收站用）、列举（导入用）、以及路径越界防护。
 */
class LocalStorageServiceTest {

    @TempDir
    Path root;

    private LocalStorageService service;
    private StorageSetting setting;

    @BeforeEach
    void setUp() {
        AppProperties properties = new AppProperties();
        com.qxfly.imagehost.service.RequestBase requestBase =
                new com.qxfly.imagehost.service.RequestBase(properties);
        // 单元测试里没有 Spring 容器，直接塞一个端口（没有请求上下文时退回本机回环）
        ReflectionTestUtils.setField(requestBase, "serverPort", 8080);
        service = new LocalStorageService(properties, requestBase);
        setting = StorageSetting.of(StorageType.LOCAL, 9L,
                Map.of("baseDir", root.toString(), "pathPrefix", "images"));
    }

    private byte[] png(int size) {
        byte[] bytes = new byte[size];
        bytes[0] = (byte) 0x89;
        bytes[1] = 'P';
        bytes[2] = 'N';
        bytes[3] = 'G';
        return bytes;
    }

    @Test
    void 上传后能读回同样的内容() {
        byte[] content = png(128);
        UploadResult result = service.upload(setting, StorageObject.ofBytes("images/a.png", "a.png", "image/png", content));

        assertThat(result.objectKey()).isEqualTo("images/a.png");
        assertThat(result.url()).isEqualTo("http://127.0.0.1:8080/files/9/images/a.png");
        assertThat(service.exists(setting, "images/a.png")).isTrue();
        assertThat(service.download(setting, "images/a.png")).isEqualTo(content);
        assertThat(Files.exists(root.resolve("images/a.png"))).isTrue();
    }

    @Test
    void 上传临时文件来源时也是原样落盘() throws IOException {
        Path source = Files.createTempFile("local-source-", ".png");
        Files.write(source, png(64));
        try {
            service.upload(setting, StorageObject.ofFile("images/b.png", "b.png", "image/png", source, 64));
            assertThat(service.download(setting, "images/b.png")).isEqualTo(png(64));
        } finally {
            Files.deleteIfExists(source);
        }
    }

    @Test
    void 搬移就是磁盘改名且会清理空目录() {
        service.upload(setting, StorageObject.ofBytes("images/2026/01/01/a.png", "a.png", "image/png", png(8)));
        service.move(setting, "images/2026/01/01/a.png", "_trash/images/2026/01/01/a.png");

        assertThat(service.exists(setting, "images/2026/01/01/a.png")).isFalse();
        assertThat(service.exists(setting, "_trash/images/2026/01/01/a.png")).isTrue();
        // 原来的日期目录已经空了，应该被顺手删掉
        assertThat(Files.exists(root.resolve("images/2026"))).isFalse();
    }

    @Test
    void 删除后空目录也会被清理() {
        service.upload(setting, StorageObject.ofBytes("images/2026/02/02/a.png", "a.png", "image/png", png(8)));
        service.delete(setting, "images/2026/02/02/a.png");

        assertThat(service.exists(setting, "images/2026/02/02/a.png")).isFalse();
        assertThat(Files.exists(root.resolve("images"))).isFalse();
    }

    @Test
    void 列举只返回图片且可过滤前缀() throws IOException {
        service.upload(setting, StorageObject.ofBytes("images/2026/a.png", "a.png", "image/png", png(8)));
        service.upload(setting, StorageObject.ofBytes("images/2026/b.jpg", "b.jpg", "image/jpeg", png(8)));
        Files.write(root.resolve("images/2026/readme.txt"), "not an image".getBytes());
        Files.write(root.resolve("images/2026/.upload-tmp.tmp"), new byte[]{1});

        List<com.qxfly.imagehost.storage.RemoteObject> all = service.list(setting, null, 100);
        assertThat(all).extracting(com.qxfly.imagehost.storage.RemoteObject::objectKey)
                .containsExactlyInAnyOrder("images/2026/a.png", "images/2026/b.jpg");
        assertThat(all.get(0).url()).isEqualTo("http://127.0.0.1:8080/files/9/" + all.get(0).objectKey());

        assertThat(service.list(setting, "images/2026/b", 100)).hasSize(1);
        assertThat(service.list(setting, null, 1)).hasSize(1);
    }

    @Test
    void 连通性校验会回显目录与可用空间() {
        service.test(setting);
        assertThat(service.testMessage(setting)).contains("目录可写").contains(root.toString());
        assertThat(service.supportsMove()).isTrue();
        assertThat(service.supportsDownload()).isTrue();
        assertThat(service.supportsList()).isTrue();
    }

    @Test
    void 对象键越界会被拒绝() {
        assertThatThrownBy(() -> service.download(setting, "../escape.png"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("非法的对象键");
        assertThatThrownBy(() -> service.upload(setting,
                StorageObject.ofBytes("images/../../escape.png", "e.png", "image/png", png(8))))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> service.exists(setting, "images//a.png"))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> service.exists(setting, "")).isInstanceOf(BizException.class);
    }

    @Test
    void 没保存的配置无法生成访问地址() {
        StorageSetting unsaved = StorageSetting.of(StorageType.LOCAL, Map.of("baseDir", root.toString()));
        assertThatThrownBy(() -> service.publicUrl(unsaved, "images/a.png"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("尚未保存");
    }

    @Test
    void 配了对外前缀就用它() {
        StorageSetting custom = StorageSetting.of(StorageType.LOCAL, 9L,
                Map.of("baseDir", root.toString(), "publicBaseUrl", "https://img.example.com/"));
        assertThat(service.publicUrl(custom, "images/a.png"))
                .isEqualTo("https://img.example.com/files/9/images/a.png");
    }
}
