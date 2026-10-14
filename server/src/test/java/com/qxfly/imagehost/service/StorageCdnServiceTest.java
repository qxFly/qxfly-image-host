package com.qxfly.imagehost.service;

import com.qxfly.imagehost.entity.StorageConfigEntity;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CDN 线路解析。只用到 config_json 里的非敏感字段，所以不需要数据库。
 */
class StorageCdnServiceTest {

    private final StorageCdnService service = new StorageCdnService(null);

    private StorageConfigEntity config(String type, String json) {
        StorageConfigEntity config = new StorageConfigEntity();
        config.setId(2L);
        config.setType(type);
        config.setConfigJson(json);
        return config;
    }

    private StorageConfigEntity github(String mode) {
        return config("GITHUB", "{\"owner\":\"qxFly\",\"repo\":\"qxfly-image\",\"branch\":\"main\","
                + "\"mode\":\"" + mode + "\"}");
    }

    @Test
    void github_contents_的内置预设按国内可用性排序() {
        assertThat(service.presets(github("contents")))
                .extracting(StorageCdnService.CdnItem::key)
                .containsExactly("gh-proxy", "ghproxy", "jsdelivr-cf", "jsdelivr");
    }

    @Test
    void releases_模式与本地存储没有内置预设() {
        assertThat(service.presets(github("releases"))).isEmpty();
        assertThat(service.presets(config("LOCAL", "{}"))).isEmpty();
        assertThat(service.presets(null)).isEmpty();
        assertThat(service.presets(config("未知道类型", "{}"))).isEmpty();
    }

    @Test
    void github_预设带上owner与仓库() {
        assertThat(service.presets(github("contents")).get(0).template())
                .isEqualTo("https://gh-proxy.com/https://raw.githubusercontent.com/qxFly/qxfly-image/main");
        assertThat(service.presets(config("GITHUB", "{\"repo\":\"r\"}"))).isEmpty();
    }

    @Test
    void 模板占位符替换与拼接() {
        assertThat(service.apply("https://cdn/{owner}/{repo}@{branch}", github("contents"), "a/b.png"))
                .isEqualTo("https://cdn/qxFly/qxfly-image@main/a/b.png");
        assertThat(service.apply("https://cdn/{owner}", github("contents"), null))
                .isEqualTo("https://cdn/qxFly");
        assertThat(service.apply("", github("contents"), "a.png")).isNull();
    }

    @Test
    void 未配置线路时三个地址都是原始地址() {
        String[] urls = service.urlsFor(github("contents"), "a.png", "https://raw/a.png");
        assertThat(urls).containsExactly("https://raw/a.png", "https://raw/a.png", "https://raw/a.png");
        assertThat(service.defaultKey(github("contents"), "preview")).isEqualTo(StorageCdnService.ORIGIN_KEY);
    }

    @Test
    void 配了默认线路就按线路解析且未知线路回落原始地址() {
        StorageConfigEntity config = github("contents");
        config.setCdnJson("{\"custom\":[{\"key\":\"mine\",\"name\":\"自建\","
                + "\"template\":\"https://my.cdn/{owner}/{repo}\",\"enabled\":true}],"
                + "\"preview\":\"mine\",\"download\":\"gh-proxy\",\"copy\":\"不存在\"}");

        String[] urls = service.urlsFor(config, "images/a.png", "https://raw/images/a.png");
        assertThat(urls[0]).isEqualTo("https://my.cdn/qxFly/qxfly-image/images/a.png");
        assertThat(urls[1]).isEqualTo("https://gh-proxy.com/https://raw.githubusercontent.com/qxFly/qxfly-image/main/images/a.png");
        // 未知线路 -> 原始地址
        assertThat(urls[2]).isEqualTo("https://raw/images/a.png");
        assertThat(service.defaultKey(config, "preview")).isEqualTo("mine");
        assertThat(service.defaultKey(config, "copy")).isEqualTo(StorageCdnService.ORIGIN_KEY);
    }

    @Test
    void 配置为空时不会抛异常() {
        assertThat(service.urlsFor(null, "a.png", "https://raw/a.png"))
                .containsExactly("https://raw/a.png", "https://raw/a.png", "https://raw/a.png");
    }
}
