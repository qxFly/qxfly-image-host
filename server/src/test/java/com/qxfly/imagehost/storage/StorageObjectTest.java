package com.qxfly.imagehost.storage;

import com.qxfly.imagehost.common.BizException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 存储对象的内容来源（内存 / 文件）。无上限上传靠的就是文件来源不整份进内存。
 */
class StorageObjectTest {

    @Test
    void 内存来源的基本行为() {
        byte[] content = "hello".getBytes();
        StorageObject object = StorageObject.ofBytes("a/b.txt", "b.txt", "text/plain", content);

        assertThat(object.objectKey()).isEqualTo("a/b.txt");
        assertThat(object.fileName()).isEqualTo("b.txt");
        assertThat(object.contentType()).isEqualTo("text/plain");
        assertThat(object.size()).isEqualTo(5);
        assertThat(object.fileBacked()).isFalse();
        assertThat(object.contentFile()).isNull();
        assertThat(object.content()).isEqualTo(content);
        assertThat(object.baseName()).isEqualTo("b.txt");
    }

    @Test
    void 文件来源不把内容放内存() throws IOException {
        Path file = Files.createTempFile("storage-object-", ".bin");
        Files.write(file, new byte[1024 * 1024]);
        try {
            StorageObject object = StorageObject.ofFile("k.png", "k.png", "image/png", file, 1024 * 1024);

            assertThat(object.fileBacked()).isTrue();
            assertThat(object.contentFile()).isEqualTo(file);
            assertThat(object.size()).isEqualTo(1024 * 1024);
            // 需要整份内容时才读（GitHub Contents / ImgBB 这类协议必须）
            assertThat(object.content()).hasSize(1024 * 1024);
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void 文件来源的长度为0时按实际文件大小算() throws IOException {
        Path file = Files.createTempFile("storage-object-", ".bin");
        Files.write(file, new byte[7]);
        try {
            assertThat(StorageObject.ofFile("k", "k", "image/png", file, 0).size()).isEqualTo(7);
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void 空内容与不存在的文件会被拒绝() {
        assertThatThrownBy(() -> StorageObject.ofBytes("k", "k", "image/png", null))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> StorageObject.ofBytes("k", "k", "image/png", new byte[0]))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> StorageObject.ofFile("k", "k", "image/png", Path.of("not-exists-xyz.bin"), 1))
                .isInstanceOf(BizException.class);
    }

    @Test
    void 兼容旧的构造方式() {
        StorageObject object = new StorageObject("k.png", "k.png", "image/png", new byte[]{1, 2, 3});
        assertThat(object.size()).isEqualTo(3);
        assertThat(object.fileBacked()).isFalse();
    }

    @Test
    void 对象键为空时baseName退回文件名() {
        StorageObject object = StorageObject.ofBytes(null, "原名.png", "image/png", new byte[]{1});
        assertThat(object.baseName()).isEqualTo("原名.png");
    }
}
