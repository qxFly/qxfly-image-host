package com.qxfly.imagehost.service;

import com.qxfly.imagehost.common.BizException;
import com.qxfly.imagehost.dto.CdnDefaultsRequest;
import com.qxfly.imagehost.dto.CdnRequest;
import com.qxfly.imagehost.dto.CdnVO;
import com.qxfly.imagehost.entity.StorageConfigEntity;
import com.qxfly.imagehost.mapper.StorageConfigMapper;
import com.qxfly.imagehost.storage.StorageType;
import com.qxfly.imagehost.storage.support.Json;
import com.qxfly.imagehost.storage.support.ObjectKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 存储配置的 CDN 管理。
 * <p>
 * 每个存储配置可以有多条 CDN 线路：系统按存储类型生成的<b>内置预设</b>（例如 GitHub 的 jsDelivr / Statically），
 * 加上用户自己加的<b>自定义前缀</b>。预览 / 下载 / 复制可以各选一条默认线路。
 * <p>
 * 配置存在 {@code storage_config.cdn_json} 里，结构见 {@link Settings}；
 * 解析成实际地址只用到 owner / repo / branch 这些非敏感字段，所以不需要解密密钥。
 * <p>
 * 注意：内置预设默认<b>不启用</b>（默认仍用原始地址）——jsDelivr 这类 CDN 有缓存，
 * 新上传的图片可能一时取不到，是否启用交给用户决定。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StorageCdnService {

    /** 原始地址（存储后端直链） */
    public static final String ORIGIN_KEY = "origin";
    /** 服务端代理下载，只在下载场景使用 */
    public static final String PROXY_KEY = "proxy";

    private static final int MAX_CUSTOM = 20;

    private final StorageConfigMapper storageConfigMapper;

    /** cdn_json 里的一项自定义 CDN */
    public record CdnItem(String key, String name, String template, boolean enabled) {
    }

    /** cdn_json 的整体结构 */
    public record Settings(List<CdnItem> custom, String preview, String download, String copy) {

        public static Settings empty() {
            return new Settings(List.of(), null, null, null);
        }

        public Settings withCustom(List<CdnItem> items) {
            return new Settings(items, preview, download, copy);
        }

        public Settings withDefaults(String previewKey, String downloadKey, String copyKey) {
            return new Settings(custom, previewKey, downloadKey, copyKey);
        }
    }

    // ================================================================ 读写配置

    public Settings read(StorageConfigEntity config) {
        if (config == null || !StringUtils.hasText(config.getCdnJson())) {
            return Settings.empty();
        }
        try {
            Settings settings = Json.read(config.getCdnJson(), Settings.class);
            if (settings == null) {
                return Settings.empty();
            }
            return new Settings(settings.custom() == null ? List.of() : settings.custom(),
                    settings.preview(), settings.download(), settings.copy());
        } catch (Exception e) {
            log.warn("解析 CDN 配置失败，按未配置处理: {}", e.getMessage());
            return Settings.empty();
        }
    }

    private void save(StorageConfigEntity config, Settings settings) {
        StorageConfigEntity update = new StorageConfigEntity();
        update.setId(config.getId());
        update.setCdnJson(Json.write(settings));
        storageConfigMapper.updateById(update);
        // 让调用方后续直接读到最新值
        config.setCdnJson(update.getCdnJson());
    }

    // ================================================================ 列表

    /** 内置预设：目前只有 GitHub contents 模式有公认的公共 CDN */
    public List<CdnItem> presets(StorageConfigEntity config) {
        if (config == null) {
            return List.of();
        }
        StorageType type;
        try {
            type = StorageType.of(config.getType());
        } catch (RuntimeException e) {
            return List.of();
        }
        if (type != StorageType.GITHUB) {
            return List.of();
        }
        Map<String, String> raw = raw(config);
        if ("releases".equalsIgnoreCase(nvl(raw.get("mode"), "contents"))) {
            return List.of();
        }
        String owner = raw.get("owner");
        String repo = raw.get("repo");
        if (!StringUtils.hasText(owner) || !StringUtils.hasText(repo)) {
            return List.of();
        }
        String branch = nvl(raw.get("branch"), "main");
        String rawBase = "https://raw.githubusercontent.com/" + owner + "/" + repo + "/" + branch;
        List<CdnItem> items = new ArrayList<>();
        // 顺序按「国内实测可用性」排：前两个在纯国内网络下能直接取到图，
        // 而且对刚上传的文件也能立刻取到（不依赖 CDN 缓存）
        items.add(new CdnItem("gh-proxy", "gh-proxy 镜像（国内可用，新图立即可取）",
                "https://gh-proxy.com/" + rawBase, true));
        items.add(new CdnItem("ghproxy", "ghproxy.net 镜像（国内可用）",
                "https://ghproxy.net/" + rawBase, true));
        items.add(new CdnItem("jsdelivr-cf", "jsDelivr 官方 · Cloudflare 节点（国内不稳定，且有缓存延迟）",
                "https://testingcf.jsdelivr.net/gh/" + owner + "/" + repo + "@" + branch, true));
        items.add(new CdnItem("jsdelivr", "jsDelivr 官方默认（国内多数地区不可直连）",
                "https://cdn.jsdelivr.net/gh/" + owner + "/" + repo + "@" + branch, true));
        return items;
    }

    /**
     * 列出该配置的所有线路：原始地址 + 内置预设 + 自定义。
     *
     * @param sampleKey 用来拼示例地址的对象键，可为空
     * @param sampleUrl 原始地址示例（一般直接用某张图的 url）
     */
    public List<CdnVO> list(StorageConfigEntity config, String sampleKey, String sampleUrl) {
        Settings settings = read(config);
        String previewKey = effectiveKey(config, settings.preview());
        String downloadKey = effectiveKey(config, settings.download());
        String copyKey = effectiveKey(config, settings.copy());

        List<CdnVO> result = new ArrayList<>();
        result.add(toVO(new CdnItem(ORIGIN_KEY, "原始地址（存储后端直链）", "", true), true,
                StringUtils.hasText(sampleUrl) ? sampleUrl : null, previewKey, downloadKey, copyKey));
        for (CdnItem item : presets(config)) {
            result.add(toVO(item, true, apply(item.template(), config, sampleKey), previewKey, downloadKey, copyKey));
        }
        for (CdnItem item : settings.custom()) {
            result.add(toVO(item, false, apply(item.template(), config, sampleKey), previewKey, downloadKey, copyKey));
        }
        return result;
    }

    private CdnVO toVO(CdnItem item, boolean builtin, String example,
                       String previewKey, String downloadKey, String copyKey) {
        CdnVO vo = new CdnVO();
        vo.setKey(item.key());
        vo.setName(item.name());
        vo.setTemplate(item.template());
        vo.setExample(example);
        vo.setOrigin(ORIGIN_KEY.equals(item.key()));
        vo.setBuiltin(builtin);
        vo.setEnabled(item.enabled());
        vo.setPreviewDefault(item.key().equals(previewKey));
        vo.setDownloadDefault(item.key().equals(downloadKey));
        vo.setCopyDefault(item.key().equals(copyKey));
        return vo;
    }

    // ================================================================ 增删改

    public List<CdnVO> addCustom(StorageConfigEntity config, CdnRequest request, String sampleKey, String sampleUrl) {
        Settings settings = read(config);
        List<CdnItem> custom = new ArrayList<>(settings.custom());
        if (custom.size() >= MAX_CUSTOM) {
            throw new BizException("自定义 CDN 最多 " + MAX_CUSTOM + " 条");
        }
        validate(request);
        CdnItem item = new CdnItem("custom-" + UUID.randomUUID().toString().substring(0, 8),
                request.getName().trim(), request.getTemplate().trim(), enabledOf(request));
        custom.add(item);
        save(config, settings.withCustom(custom));
        return list(config, sampleKey, sampleUrl);
    }

    public List<CdnVO> updateCustom(StorageConfigEntity config, String key, CdnRequest request, String sampleKey, String sampleUrl) {
        Settings settings = read(config);
        validate(request);
        List<CdnItem> custom = new ArrayList<>();
        boolean found = false;
        for (CdnItem item : settings.custom()) {
            if (item.key().equals(key)) {
                found = true;
                custom.add(new CdnItem(item.key(), request.getName().trim(), request.getTemplate().trim(), enabledOf(request)));
            } else {
                custom.add(item);
            }
        }
        if (!found) {
            throw BizException.notFound("自定义 CDN 不存在");
        }
        save(config, settings.withCustom(custom));
        return list(config, sampleKey, sampleUrl);
    }

    public List<CdnVO> deleteCustom(StorageConfigEntity config, String key, String sampleKey, String sampleUrl) {
        Settings settings = read(config);
        List<CdnItem> custom = settings.custom().stream().filter(item -> !item.key().equals(key)).toList();
        if (custom.size() == settings.custom().size()) {
            throw BizException.notFound("自定义 CDN 不存在");
        }
        // 被删掉的线路如果正好是某个用途的默认，就退回原始地址
        String preview = key.equals(settings.preview()) ? null : settings.preview();
        String download = key.equals(settings.download()) ? null : settings.download();
        String copy = key.equals(settings.copy()) ? null : settings.copy();
        save(config, new Settings(custom, preview, download, copy));
        return list(config, sampleKey, sampleUrl);
    }

    public List<CdnVO> setDefaults(StorageConfigEntity config, CdnDefaultsRequest request, String sampleKey, String sampleUrl) {
        Settings settings = read(config);
        save(config, settings.withDefaults(
                normalize(config, request.getPreview()),
                normalize(config, request.getDownload()),
                normalize(config, request.getCopy())));
        return list(config, sampleKey, sampleUrl);
    }

    // ================================================================ 地址解析

    /** 某个用途实际使用的线路 key（未配置时是原始地址） */
    public String defaultKey(StorageConfigEntity config, String purpose) {
        Settings settings = read(config);
        String value = switch (purpose) {
            case "preview" -> settings.preview();
            case "download" -> settings.download();
            case "copy" -> settings.copy();
            default -> null;
        };
        return effectiveKey(config, value);
    }

    /**
     * 一次算出「预览 / 下载 / 复制」三个地址（未配置的用途返回原始地址）。
     * 列表页会按配置逐张调用，所以这里只读一次配置。
     */
    public String[] urlsFor(StorageConfigEntity config, String objectKey, String originUrl) {
        if (config == null) {
            return new String[]{originUrl, originUrl, originUrl};
        }
        Settings settings = read(config);
        List<CdnItem> items = allItems(config, settings);
        return new String[]{
                resolve(items, settings.preview(), config, objectKey, originUrl),
                resolve(items, settings.download(), config, objectKey, originUrl),
                resolve(items, settings.copy(), config, objectKey, originUrl)
        };
    }

    private String resolve(List<CdnItem> items, String key, StorageConfigEntity config,
                           String objectKey, String originUrl) {
        if (!StringUtils.hasText(key) || ORIGIN_KEY.equals(key)) {
            return originUrl;
        }
        CdnItem item = items.stream()
                .filter(i -> i.key().equals(key) && i.enabled())
                .findFirst()
                .orElse(null);
        if (item == null) {
            return originUrl;
        }
        String url = apply(item.template(), config, objectKey);
        return StringUtils.hasText(url) ? url : originUrl;
    }

    /** 内置预设 + 自定义（一次算好，避免重复解析 JSON） */
    private List<CdnItem> allItems(StorageConfigEntity config, Settings settings) {
        List<CdnItem> items = new ArrayList<>(presets(config));
        items.addAll(settings.custom());
        return items;
    }

    /** 把模板里的占位符替换掉并拼上对象键 */
    public String apply(String template, StorageConfigEntity config, String objectKey) {
        if (!StringUtils.hasText(template)) {
            return null;
        }
        Map<String, String> raw = raw(config);
        String prefix = template
                .replace("{owner}", nvl(raw.get("owner"), ""))
                .replace("{repo}", nvl(raw.get("repo"), ""))
                .replace("{branch}", nvl(raw.get("branch"), "main"));
        if (!StringUtils.hasText(objectKey)) {
            return prefix;
        }
        return ObjectKeys.joinUrl(prefix, objectKey);
    }

    // ================================================================ 内部工具

    private CdnItem find(StorageConfigEntity config, String key) {
        if (!StringUtils.hasText(key) || ORIGIN_KEY.equals(key)) {
            return null;
        }
        for (CdnItem item : presets(config)) {
            if (item.key().equals(key)) {
                return item;
            }
        }
        return read(config).custom().stream().filter(item -> item.key().equals(key)).findFirst().orElse(null);
    }

    /** 把配置里的默认值收敛成「一定存在的 key」：origin 或某个已启用的线路 */
    private String effectiveKey(StorageConfigEntity config, String value) {
        if (!StringUtils.hasText(value) || ORIGIN_KEY.equals(value)) {
            return ORIGIN_KEY;
        }
        CdnItem item = find(config, value);
        return item != null && item.enabled() ? value : ORIGIN_KEY;
    }

    private String normalize(StorageConfigEntity config, String value) {
        String key = effectiveKey(config, value);
        return ORIGIN_KEY.equals(key) ? null : key;
    }

    private void validate(CdnRequest request) {
        String template = request.getTemplate() == null ? "" : request.getTemplate().trim().toLowerCase(Locale.ROOT);
        if (!template.startsWith("http://") && !template.startsWith("https://")) {
            throw new BizException("地址前缀必须以 http:// 或 https:// 开头");
        }
    }

    private boolean enabledOf(CdnRequest request) {
        return request.getEnabled() == null || request.getEnabled();
    }

    private Map<String, String> raw(StorageConfigEntity config) {
        String json = config.getConfigJson();
        if (!StringUtils.hasText(json)) {
            return Map.of();
        }
        try {
            return Json.readStringMap(json);
        } catch (RuntimeException e) {
            return Map.of();
        }
    }

    private String nvl(String value, String fallback) {
        return StringUtils.hasText(value) ? value : fallback;
    }
}
