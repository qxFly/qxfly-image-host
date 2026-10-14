package com.qxfly.imagehost.storage.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.qxfly.imagehost.common.BizException;
import com.qxfly.imagehost.config.AppProperties;
import com.qxfly.imagehost.service.AppSettingService;
import com.qxfly.imagehost.storage.RemoteObject;
import com.qxfly.imagehost.storage.StorageObject;
import com.qxfly.imagehost.storage.StorageService;
import com.qxfly.imagehost.storage.StorageSetting;
import com.qxfly.imagehost.storage.StorageType;
import com.qxfly.imagehost.storage.UploadResult;
import com.qxfly.imagehost.storage.support.HttpSupport;
import com.qxfly.imagehost.storage.support.HttpSupport.HttpResult;
import com.qxfly.imagehost.storage.support.Json;
import com.qxfly.imagehost.storage.support.ObjectKeys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * GitHub 图床。
 * <p>
 * contents 模式：图片作为仓库文件提交（适合小仓库存图，可用 jsDelivr 加速）；<br>
 * releases 模式：图片作为 Release 附件（不占用仓库体积，单文件上限更高）。
 */
@Slf4j
@Component
@lombok.RequiredArgsConstructor
public class GithubStorageService implements StorageService {

    private final com.qxfly.imagehost.storage.support.GithubRepoLock repoLock;
    private final AppProperties appProperties;

    private static final String DEFAULT_API = "https://api.github.com";
    private static final String UPLOAD_API = "https://uploads.github.com";
    /** 文件树逐级遍历时的最大 API 调用次数，防止超大仓库把配额耗光 */
    private static final int MAX_TREE_CALLS = 500;
    /** Release 附件最大翻页数（每页 100 条） */
    private static final int MAX_ASSET_PAGES = 1000;
    /** 并发写同一分支冲突（409 / 422）时的重试次数 */
    private static final int CONFLICT_RETRY_TIMES = 5;

    @Override
    public StorageType type() {
        return StorageType.GITHUB;
    }

    @Override
    public UploadResult upload(StorageSetting setting, StorageObject object) {
        if ("releases".equalsIgnoreCase(setting.get("mode", "contents"))) {
            return uploadToRelease(setting, object);
        }
        return uploadToContents(setting, object);
    }

    // ==================== contents 模式 ====================

    private UploadResult uploadToContents(StorageSetting setting, StorageObject object) {
        String path = object.objectKey();
        // Contents API 只能整份 Base64 提交，内容必须进内存，所以这里设一道上限；
        // 更大的文件请用 Releases 模式（流式）或对象存储 / 本地存储
        long inlineLimit = appProperties.getUpload().getGithubInlineLimit();
        if (inlineLimit > 0 && object.size() > inlineLimit) {
            throw new BizException("GitHub contents 模式单文件上限 "
                    + AppSettingService.readable(inlineLimit) + "，当前 " + AppSettingService.readable(object.size())
                    + "。请改用 Releases 模式，或换对象存储 / 本地存储。");
        }
        ObjectNode body = Json.mapper().createObjectNode();
        body.put("message", "chore(qxfly-image-host): upload " + object.fileName());
        body.put("content", Base64.getEncoder().encodeToString(object.content()));
        body.put("branch", setting.get("branch", "main"));

        HttpResult result = putContent(setting, path, Json.write(body));
        if (!result.isOk()) {
            throw new BizException("上传到 GitHub 仓库失败 (HTTP " + result.status() + "): "
                    + errorOf(result) + "。请确认 Token 具有 Contents 读写权限、分支 "
                    + setting.get("branch", "main") + " 已存在"
                    + "（多张同时上传时的并发冲突会自动重试 " + CONFLICT_RETRY_TIMES + " 次）");
        }

        return UploadResult.of(path, publicUrl(setting, path));
    }

    /**
     * 写文件内容，遇到「分支被并发提交推走」的冲突时退避重试。
     * <p>
     * Contents API 每写一个文件就要向分支提交一次，所以**多张图片同时上传时必然撞车**：
     * 落后的那个会收到 409 <code>is at xxx but expected yyy</code>，重试一次就能过。
     * 这里还处理一种边界：请求其实写成功了、但响应在路上丢了（超时），
     * 重试会拿到 422，这时确认文件确实存在就按成功处理，避免上传"失败"却留下一个孤儿文件。
     */
    private HttpResult putContent(StorageSetting setting, String path, String json) {
        // 同一个仓库分支的提交串行化：并发写必然 409，排队比让用户等重试可靠得多
        return repoLock.withLock(setting, () -> putContentLocked(setting, path, json));
    }

    private HttpResult putContentLocked(StorageSetting setting, String path, String json) {
        HttpResult last = null;
        for (int attempt = 1; attempt <= CONFLICT_RETRY_TIMES; attempt++) {
            try {
                last = HttpSupport.putJson(contentsUrl(setting, path), headers(setting), json);
                if (last.isOk()) {
                    return last;
                }
                if (!isRetryableWrite(last)) {
                    return last;
                }
            } catch (RuntimeException e) {
                if (attempt == CONFLICT_RETRY_TIMES) {
                    throw e;
                }
                log.warn("上传 {} 第 {} 次请求异常，重试: {}", path, attempt, e.getMessage());
            }
            if (attempt < CONFLICT_RETRY_TIMES) {
                long wait = 400L * attempt + (long) (Math.random() * 300);
                log.warn("上传 {} 命中 GitHub 并发冲突 (HTTP {})，第 {} 次重试（等待 {}ms）",
                        path, last == null ? 0 : last.status(), attempt, wait);
                try {
                    Thread.sleep(wait);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new BizException("上传被中断");
                }
            }
        }
        if (last != null && last.status() == 422 && fileExists(setting, path)) {
            log.warn("上传 {} 返回 422 但文件已存在（上一次请求可能其实成功了），按成功处理", path);
            return new HttpResult(201, last.body());
        }
        return last;
    }

    /** 这些状态是并发冲突 / 限流 / 服务端抖动，值得重试 */
    private boolean isRetryableWrite(HttpResult result) {
        int status = result.status();
        if (status == 409 || status == 429 || status >= 500) {
            return true;
        }
        if (status == 422) {
            String body = result.body() == null ? "" : result.body().toLowerCase(Locale.ROOT);
            return body.contains("sha") || body.contains("expected") || body.contains("conflict");
        }
        return false;
    }

    private boolean fileExists(StorageSetting setting, String path) {
        try {
            HttpResult detail = HttpSupport.get(contentsUrl(setting, path) + "?ref="
                    + HttpSupport.encode(setting.get("branch", "main")), headers(setting));
            return detail.isOk();
        } catch (RuntimeException e) {
            return false;
        }
    }

    // ==================== 列举已有文件（用于导入） ====================

    @Override
    public boolean supportsList() {
        return true;
    }

    @Override
    public boolean supportsDownload() {
        return true;
    }

    /** 私有仓库也能下：contents 走 raw accept，releases 走附件 API 的 octet-stream */
    @Override
    public byte[] download(StorageSetting setting, String objectKey) {
        if ("releases".equalsIgnoreCase(setting.get("mode", "contents"))) {
            return downloadReleaseAsset(setting, objectKey);
        }
        Map<String, String> rawHeaders = headers(setting);
        rawHeaders.put("Accept", "application/vnd.github.raw");
        HttpSupport.BinaryResult result = HttpSupport.getBinary(contentsUrl(setting, objectKey), rawHeaders);
        if (result.status() < 200 || result.status() >= 300) {
            throw new BizException("从 GitHub 下载失败 (HTTP " + result.status() + ")");
        }
        return result.body();
    }

    private byte[] downloadReleaseAsset(StorageSetting setting, String objectKey) {
        Long releaseId = findReleaseId(setting);
        if (releaseId == null) {
            throw new BizException("GitHub Release 不存在，无法下载：" + setting.get("releaseTag", "pichost"));
        }
        HttpResult assets = HttpSupport.get(UPLOAD_API + "/repos/" + setting.get("owner") + "/"
                + setting.get("repo") + "/releases/" + releaseId + "/assets?per_page=100", headers(setting));
        if (!assets.isOk()) {
            throw new BizException("读取 GitHub Release 附件失败: " + errorOf(assets));
        }
        for (JsonNode asset : Json.tree(assets.body())) {
            if (objectKey.equals(asset.path("name").asText())) {
                Map<String, String> binaryHeaders = headers(setting);
                binaryHeaders.put("Accept", "application/octet-stream");
                HttpSupport.BinaryResult result = HttpSupport.getBinary(asset.path("url").asText(), binaryHeaders);
                if (result.status() < 200 || result.status() >= 300) {
                    throw new BizException("下载 GitHub Release 附件失败 (HTTP " + result.status() + ")");
                }
                return result.body();
            }
        }
        throw new BizException("Release 中找不到附件：" + objectKey);
    }

    @Override
    public String publicUrl(StorageSetting setting, String objectKey) {
        if ("releases".equalsIgnoreCase(setting.get("mode", "contents"))) {
            return "https://github.com/" + setting.get("owner") + "/" + setting.get("repo")
                    + "/releases/download/" + setting.get("releaseTag", "pichost") + "/" + objectKey;
        }
        String cdnPrefix = setting.get("cdnPrefix");
        if (cdnPrefix != null) {
            return ObjectKeys.joinUrl(cdnPrefix, objectKey);
        }
        return "https://raw.githubusercontent.com/" + setting.get("owner") + "/" + setting.get("repo")
                + "/" + setting.get("branch", "main") + "/" + objectKey;
    }

    @Override
    public List<RemoteObject> list(StorageSetting setting, String prefix, int limit) {
        if ("releases".equalsIgnoreCase(setting.get("mode", "contents"))) {
            return listReleaseAssets(setting, prefix, limit);
        }
        return listContents(setting, prefix, limit);
    }

    /**
     * 拉取仓库里的全部文件。
     * <p>
     * 先尝试一次 recursive 调用（GitHub 单次最多返回 10 万条 / 7MB）；
     * 一旦返回 truncated=true，说明树太大被截断，就退化成"先取当前层级、再逐个子树递归"，
     * 这样无论仓库有多大都能取全，而不是只拿前 N 条。
     */
    private List<RemoteObject> listContents(StorageSetting setting, String prefix, int max) {
        java.util.ArrayList<RemoteObject> objects = new java.util.ArrayList<>();
        int[] calls = new int[1];
        String branch = setting.get("branch", "main");
        walkTree(setting, branch, "", prefix, max, objects, calls);
        log.info("GitHub 文件树遍历完成：命中 {} 个图片文件，共 {} 次 API 调用", objects.size(), calls[0]);
        return objects;
    }

    private void walkTree(StorageSetting setting, String ref, String basePath, String prefix, int max,
                          List<RemoteObject> out, int[] calls) {
        if (out.size() >= max) {
            return;
        }
        if (calls[0] >= MAX_TREE_CALLS) {
            log.warn("GitHub 文件树遍历已达 {} 次请求上限，提前结束（可调大 app.upload.import-max-files）", MAX_TREE_CALLS);
            return;
        }
        calls[0]++;
        JsonNode root = fetchTree(setting, ref, true);
        if (!root.path("truncated").asBoolean(false)) {
            collectBlobs(setting, root.path("tree"), basePath, prefix, max, out);
            return;
        }
        // 被截断：只取当前层级，再逐个子目录递归
        log.info("GitHub 文件树在 '{}' 处被截断，改为逐级遍历", basePath.isEmpty() ? "/" : basePath);
        JsonNode level = fetchTree(setting, ref, false);
        for (JsonNode node : level.path("tree")) {
            if (out.size() >= max) {
                return;
            }
            String path = node.path("path").asText();
            String full = basePath + path;
            if ("tree".equals(node.path("type").asText())) {
                walkTree(setting, node.path("sha").asText(), full + "/", prefix, max, out, calls);
            } else if ("blob".equals(node.path("type").asText())) {
                addBlob(setting, node, full, prefix, max, out);
            }
        }
    }

    private JsonNode fetchTree(StorageSetting setting, String ref, boolean recursive) {
        String url = api(setting) + "/repos/" + setting.get("owner") + "/" + setting.get("repo")
                + "/git/trees/" + HttpSupport.encode(ref) + (recursive ? "?recursive=1" : "");
        HttpResult result = HttpSupport.get(url, headers(setting));
        if (!result.isOk()) {
            throw new BizException("读取 GitHub 仓库文件树失败 (HTTP " + result.status() + "): " + errorOf(result)
                    + "。请确认分支存在且 Token 有 Contents 读取权限");
        }
        return Json.tree(result.body());
    }

    private void collectBlobs(StorageSetting setting, JsonNode nodes, String basePath, String prefix,
                              int max, List<RemoteObject> out) {
        for (JsonNode node : nodes) {
            if (out.size() >= max) {
                return;
            }
            if (!"blob".equals(node.path("type").asText())) {
                continue;
            }
            addBlob(setting, node, basePath + node.path("path").asText(), prefix, max, out);
        }
    }

    private void addBlob(StorageSetting setting, JsonNode node, String fullPath, String prefix,
                         int max, List<RemoteObject> out) {
        if (out.size() >= max || fullPath.isEmpty()) {
            return;
        }
        if (!com.qxfly.imagehost.storage.support.ImageTypes.isImage(fullPath)) {
            return;
        }
        if (prefix != null && !prefix.isEmpty() && !fullPath.startsWith(prefix)) {
            return;
        }
        out.add(new RemoteObject(fullPath, node.path("size").asLong(0L), publicUrl(setting, fullPath), null));
    }

    private List<RemoteObject> listReleaseAssets(StorageSetting setting, String prefix, int limit) {
        Long releaseId = findReleaseId(setting);
        if (releaseId == null) {
            return java.util.List.of();
        }
        java.util.ArrayList<RemoteObject> objects = new java.util.ArrayList<>();
        int page = 1;
        // 每页 100 条，最多翻 1000 页（10 万个附件），足以覆盖任何实际场景
        while (objects.size() < limit && page <= MAX_ASSET_PAGES) {
            HttpResult result = HttpSupport.get(UPLOAD_API + "/repos/" + setting.get("owner") + "/"
                    + setting.get("repo") + "/releases/" + releaseId + "/assets?per_page=100&page=" + page,
                    headers(setting));
            if (!result.isOk()) {
                throw new BizException("读取 GitHub Release 附件失败 (HTTP " + result.status() + "): " + errorOf(result));
            }
            JsonNode assets = Json.tree(result.body());
            if (!assets.isArray() || assets.isEmpty()) {
                break;
            }
            for (JsonNode asset : assets) {
                String name = asset.path("name").asText();
                if (name.isEmpty() || !com.qxfly.imagehost.storage.support.ImageTypes.isImage(name)) {
                    continue;
                }
                if (prefix != null && !prefix.isEmpty() && !name.startsWith(prefix)) {
                    continue;
                }
                String downloadUrl = asset.path("browser_download_url").asText(null);
                objects.add(new RemoteObject(name, asset.path("size").asLong(0L),
                        downloadUrl == null ? publicUrl(setting, name) : downloadUrl,
                        asset.path("created_at").asText(null)));
                if (objects.size() >= limit) {
                    break;
                }
            }
            if (assets.size() < 100) {
                break;
            }
            page++;
        }
        return objects;
    }

    private void deleteFromContents(StorageSetting setting, String objectKey) {
        HttpResult detail = HttpSupport.get(contentsUrl(setting, objectKey) + "?ref="
                + HttpSupport.encode(setting.get("branch", "main")), headers(setting));
        if (detail.status() == 404) {
            return;
        }
        if (!detail.isOk()) {
            throw new BizException("读取 GitHub 文件信息失败: " + errorOf(detail));
        }
        String sha = Json.tree(detail.body()).path("sha").asText(null);
        if (sha == null) {
            return;
        }
        ObjectNode body = Json.mapper().createObjectNode();
        body.put("message", "chore(qxfly-image-host): delete " + ObjectKeys.fileName(objectKey));
        body.put("sha", sha);
        body.put("branch", setting.get("branch", "main"));
        HttpResult result = HttpSupport.deleteJson(contentsUrl(setting, objectKey), headers(setting), Json.write(body));
        if (!result.isOk()) {
            throw new BizException("删除 GitHub 仓库文件失败: " + errorOf(result));
        }
    }

    // ==================== releases 模式 ====================

    private UploadResult uploadToRelease(StorageSetting setting, StorageObject object) {
        long releaseId = ensureRelease(setting);
        String assetName = object.baseName();
        String url = UPLOAD_API + "/repos/" + setting.get("owner") + "/" + setting.get("repo")
                + "/releases/" + releaseId + "/assets?name=" + HttpSupport.encode(assetName);
        String contentType = object.contentType() == null ? "application/octet-stream" : object.contentType();

        // 附件是整份二进制 body，直接用文件做请求体（HttpClient 按流发送）
        HttpResult result = object.fileBacked()
                ? HttpSupport.postBinaryFile(url, headers(setting), contentType, object.contentFile(), object.size())
                : HttpSupport.postBinary(url, headers(setting), contentType, object.content());
        if (!result.isOk()) {
            throw new BizException("上传到 GitHub Release 失败 (HTTP " + result.status() + "): " + errorOf(result));
        }
        String downloadUrl = Json.tree(result.body()).path("browser_download_url").asText(null);
        if (downloadUrl == null) {
            downloadUrl = "https://github.com/" + setting.get("owner") + "/" + setting.get("repo")
                    + "/releases/download/" + setting.get("releaseTag", "pichost") + "/" + assetName;
        }
        return UploadResult.of(assetName, downloadUrl);
    }

    private void deleteFromRelease(StorageSetting setting, String objectKey) {
        Long releaseId = findReleaseId(setting);
        if (releaseId == null) {
            return;
        }
        HttpResult assets = HttpSupport.get(UPLOAD_API + "/repos/" + setting.get("owner") + "/" + setting.get("repo")
                + "/releases/" + releaseId + "/assets?per_page=100", headers(setting));
        if (!assets.isOk()) {
            throw new BizException("读取 GitHub Release 附件失败: " + errorOf(assets));
        }
        for (JsonNode asset : Json.tree(assets.body())) {
            if (objectKey.equals(asset.path("name").asText())) {
                HttpResult result = HttpSupport.deleteJson(asset.path("url").asText(), headers(setting), null);
                if (!result.isOk() && result.status() != 404) {
                    throw new BizException("删除 GitHub Release 附件失败: " + errorOf(result));
                }
                return;
            }
        }
    }

    /** 获取 Release，不存在则创建 */
    private long ensureRelease(StorageSetting setting) {
        Long existing = findReleaseId(setting);
        if (existing != null) {
            return existing;
        }
        String tag = setting.get("releaseTag", "pichost");
        ObjectNode body = Json.mapper().createObjectNode();
        body.put("tag_name", tag);
        body.put("name", tag);
        body.put("body", "由 qxfly-image-host 自动创建的图片存储 Release");
        body.put("draft", false);
        body.put("prerelease", false);

        HttpResult created = HttpSupport.postJson(releaseUrl(setting), headers(setting), Json.write(body));
        if (!created.isOk()) {
            throw new BizException("创建 GitHub Release 失败 (HTTP " + created.status() + "): " + errorOf(created));
        }
        return Json.tree(created.body()).path("id").asLong();
    }

    private Long findReleaseId(StorageSetting setting) {
        String tag = setting.get("releaseTag", "pichost");
        HttpResult result = HttpSupport.get(
                api(setting) + "/repos/" + setting.get("owner") + "/" + setting.get("repo")
                        + "/releases/tags/" + HttpSupport.encode(tag), headers(setting));
        if (result.status() == 404) {
            return null;
        }
        if (!result.isOk()) {
            throw new BizException("查询 GitHub Release 失败 (HTTP " + result.status() + "): " + errorOf(result));
        }
        return Json.tree(result.body()).path("id").asLong();
    }

    // ==================== 通用 ====================

    @Override
    public void delete(StorageSetting setting, String objectKey) {
        if (objectKey == null || objectKey.isBlank()) {
            return;
        }
        if ("releases".equalsIgnoreCase(setting.get("mode", "contents"))) {
            deleteFromRelease(setting, objectKey);
        } else {
            deleteFromContents(setting, objectKey);
        }
    }

    @Override
    public void test(StorageSetting setting) {
        HttpResult result = HttpSupport.get(
                api(setting) + "/repos/" + setting.get("owner") + "/" + setting.get("repo"), headers(setting));
        if (result.status() == 401) {
            throw new BizException("GitHub Token 无效或已过期");
        }
        if (result.status() == 404) {
            throw new BizException("仓库不存在或 Token 无权访问: " + setting.get("owner") + "/" + setting.get("repo"));
        }
        if (!result.isOk()) {
            throw new BizException("连接 GitHub 失败 (HTTP " + result.status() + "): " + errorOf(result));
        }
        if ("releases".equalsIgnoreCase(setting.get("mode", "contents"))) {
            findReleaseId(setting);
        }
    }

    private String api(StorageSetting setting) {
        String base = setting.get("apiBase", DEFAULT_API);
        return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    private String contentsUrl(StorageSetting setting, String path) {
        return api(setting) + "/repos/" + setting.get("owner") + "/" + setting.get("repo")
                + "/contents/" + encodePath(path);
    }

    private String releaseUrl(StorageSetting setting) {
        return api(setting) + "/repos/" + setting.get("owner") + "/" + setting.get("repo") + "/releases";
    }

    private Map<String, String> headers(StorageSetting setting) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer " + setting.get("token"));
        headers.put("Accept", "application/vnd.github+json");
        headers.put("X-GitHub-Api-Version", "2022-11-28");
        return headers;
    }

    private String encodePath(String path) {
        StringBuilder builder = new StringBuilder();
        for (String segment : path.split("/")) {
            if (segment.isEmpty()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append('/');
            }
            builder.append(HttpSupport.encode(segment));
        }
        return builder.toString();
    }

    private String errorOf(HttpResult result) {
        try {
            JsonNode node = Json.tree(result.body());
            if (node.hasNonNull("message")) {
                return node.path("message").asText();
            }
        } catch (RuntimeException ignored) {
            // 非 JSON 响应，回退为原文
        }
        String body = result.body();
        return body == null || body.isBlank() ? "无响应内容" : body.substring(0, Math.min(200, body.length()));
    }
}
