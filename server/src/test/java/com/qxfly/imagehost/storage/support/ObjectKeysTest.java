package com.qxfly.imagehost.storage.support;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

import static org.assertj.core.api.Assertions.assertThat;

class ObjectKeysTest {

    @Test
    void 对象键按日期分目录且保留扩展名() {
        String key = ObjectKeys.build("images/", "photo.JPG");
        String today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy/MM/dd"));

        assertThat(key).startsWith("images/" + today + "/");
        assertThat(key).endsWith(".jpg");
        assertThat(key).matches("images/\\d{4}/\\d{2}/\\d{2}/[0-9a-f]{32}\\.jpg");
    }

    @Test
    void 分片号插入三位目录() {
        assertThat(ObjectKeys.build("images/", "a.png", 3)).contains("/003/");
        assertThat(ObjectKeys.build("images/", "a.png", 0)).doesNotContain("/000/");
    }

    @Test
    void 没有扩展名时只用uuid() {
        String name = ObjectKeys.randomFileName("noext");
        assertThat(name).hasSize(32).doesNotContain(".");
    }

    @Test
    void 扩展名取自最后一个点并做清洗() {
        assertThat(ObjectKeys.extension("a.tar.gz")).isEqualTo("gz");
        assertThat(ObjectKeys.extension("a.")).isEmpty();
        assertThat(ObjectKeys.extension("noext")).isEmpty();
        assertThat(ObjectKeys.extension(null)).isEmpty();
        assertThat(ObjectKeys.extension("a.PNG")).isEqualTo("png");
        assertThat(ObjectKeys.extension("中文名.jpg")).isEqualTo("jpg");
    }

    @Test
    void 取文件名() {
        assertThat(ObjectKeys.fileName("a/b/c.png")).isEqualTo("c.png");
        assertThat(ObjectKeys.fileName("c.png")).isEqualTo("c.png");
        assertThat(ObjectKeys.fileName(null)).isEmpty();
    }

    @Test
    void 目录相对化() {
        assertThat(ObjectKeys.relativeDirectory("images/2026/01/01/a.png", "images/")).isEqualTo("2026/01/01/");
        assertThat(ObjectKeys.relativeDirectory("a.png", "")).isEmpty();
        assertThat(ObjectKeys.relativeDirectory(null, "")).isEmpty();
    }

    @Test
    void 拼地址不会出现双斜杠() {
        assertThat(ObjectKeys.joinUrl("https://x.com/", "/a/b.png")).isEqualTo("https://x.com/a/b.png");
        assertThat(ObjectKeys.joinUrl("https://x.com", "a/b.png")).isEqualTo("https://x.com/a/b.png");
        assertThat(ObjectKeys.joinUrl("", "a.png")).isEqualTo("a.png");
        assertThat(ObjectKeys.joinUrl(null, "a.png")).isEqualTo("a.png");
    }

    @Test
    void LIKE通配符会被转义() {
        assertThat(ObjectKeys.escapeLike("a%b_c\\d")).isEqualTo("a\\%b\\_c\\\\d");
    }
}
