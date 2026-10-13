package com.pichost.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.pichost.common.BizException;
import com.pichost.config.AppProperties;
import com.pichost.storage.StorageSetting;
import com.pichost.storage.support.HttpSupport;
import com.pichost.storage.support.HttpSupport.HttpResult;
import com.pichost.storage.support.Json;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntConsumer;

/**
 * GitHub 仓库内的“引用式搬移”。
 * <p>
 * 同仓库换目录不需要搬运内容：Git Data API 允许新 tree 直接引用<b>原有 blob 的 SHA</b>，
 * 所以任意数量的文件只要几次 API 调用、一个提交就能搬完，既不下载也不上传，也不会产生重复文件。
 * <p>
 * 两个地方共用它：仓库目录整理（RepoMoveService）与图库批量迁移的同仓库场景（MigrationService）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GithubTreeMover {

    private static final String DEFAULT_API = "https://api.github.com";

    private final AppProperties appProperties;

    /** 要搬的一个文件 */
    public record Item(String oldPath, String newPath, String blobSha) {
    }

    /** 仓库当前快照：所有 blob 的路径与 sha */
    public record Snapshot(Map<String, String> blobShas, Set<String> paths) {
    }

    /** 拉一次完整文件树，得到 路径 -> blob sha */
    public Snapshot snapshot(StorageSetting setting) {
        HttpResult result = HttpSupport.get(
                api(setting) + "/repos/" + setting.get("owner") + "/" + setting.get("repo")
                        + "/git/trees/" + branch(setting) + "?recursive=1", headers(setting));
        result.requireOk("读取仓库文件树");
        JsonNode root = Json.tree(result.body());
        if (root.path("truncated").asBoolean(false)) {
            throw new BizException("仓库文件树被 GitHub 截断了（仓库过大），无法一次性处理");
        }
        Map<String, String> shas = new HashMap<>();
        for (JsonNode node : root.path("tree")) {
            if ("blob".equals(node.path("type").asText())) {
                shas.put(node.path("path").asText(), node.path("sha").asText());
            }
        }
        return new Snapshot(shas, new HashSet<>(shas.keySet()));
    }

    /**
     * 引用原有 blob 把文件从 oldPath 搬到 newPath：分批建 tree + commit，最后更新一次分支引用。
     * 失败不会留下痕迹 —— tree / commit 在更新分支引用之前都是悬空对象。
     *
     * @param onBatchDone 每完成一批回调一次（用于上报进度）
     * @return 真正搬走的文件
     */
    public List<Item> move(StorageSetting setting, List<Item> items, IntConsumer onBatchDone) {
        if (items.isEmpty()) {
            return List.of();
        }
        int batchFiles = Math.max(1, appProperties.getRepoMove().getBatchFiles());
        String headSha = headCommit(setting);
        String baseTree = commitTree(setting, headSha);
        log.info("仓库内搬移 {} 个文件，每批 {} 个（{} 条 tree 记录）", items.size(), batchFiles, batchFiles * 2);

        String parent = headSha;
        List<Item> done = new ArrayList<>(items.size());
        for (int start = 0; start < items.size(); start += batchFiles) {
            int end = Math.min(start + batchFiles, items.size());
            List<Map<String, Object>> entries = new ArrayList<>();
            for (Item item : items.subList(start, end)) {
                Map<String, Object> add = new HashMap<>();
                add.put("path", item.newPath());
                add.put("mode", "100644");
                add.put("type", "blob");
                add.put("sha", item.blobSha());
                entries.add(add);

                Map<String, Object> remove = new HashMap<>();
                remove.put("path", item.oldPath());
                remove.put("mode", "100644");
                remove.put("type", "blob");
                remove.put("sha", null);
                entries.add(remove);
            }
            Map<String, Object> treeBody = new LinkedHashMap<>();
            treeBody.put("base_tree", baseTree);
            treeBody.put("tree", entries);
            String treeJson = Json.write(treeBody);
            log.info("第 {} 批：{} 个文件（{} ~ {}），请求 {} KB",
                    done.size() / batchFiles + 1, end - start, start + 1, end, treeJson.length() / 1024);
            String newTree = createObject(treeUrl(setting), headers(setting), treeJson, "创建目录树");

            Map<String, Object> commitBody = new LinkedHashMap<>();
            commitBody.put("message", "chore: 移动文件 " + (end - start) + " 个");
            commitBody.put("tree", newTree);
            commitBody.put("parents", List.of(parent));
            parent = createObject(commitUrl(setting), headers(setting),
                    Json.write(commitBody), "创建提交");

            baseTree = newTree;
            done.addAll(items.subList(start, end));
            if (onBatchDone != null) {
                onBatchDone.accept(end - start);
            }
        }

        Map<String, Object> refBody = new LinkedHashMap<>();
        refBody.put("sha", parent);
        refBody.put("force", false);
        // 读取用 /git/ref/（单数），更新必须用 /git/refs/（复数）
        HttpSupport.patchJson(refsUrl(setting), headers(setting), Json.write(refBody))
                .requireOk("移动分支引用");
        log.info("仓库内搬移完成：{} 个文件，HEAD -> {}", done.size(), parent);
        return done;
    }

    /**
     * 批量删除文件：分批建 tree（sha=null 表示删除）+ commit，最后更新一次分支引用。
     *
     * @return 实际删除的文件数
     */
    public int delete(StorageSetting setting, List<String> paths, IntConsumer onBatchDone) {
        if (paths.isEmpty()) {
            return 0;
        }
        int batchFiles = Math.max(1, appProperties.getRepoMove().getBatchFiles());
        String headSha = headCommit(setting);
        String baseTree = commitTree(setting, headSha);
        String parent = headSha;
        int done = 0;
        for (int start = 0; start < paths.size(); start += batchFiles) {
            int end = Math.min(start + batchFiles, paths.size());
            List<Map<String, Object>> entries = new ArrayList<>();
            for (String path : paths.subList(start, end)) {
                Map<String, Object> remove = new HashMap<>();
                remove.put("path", path);
                remove.put("mode", "100644");
                remove.put("type", "blob");
                remove.put("sha", null);
                entries.add(remove);
            }
            Map<String, Object> treeBody = new LinkedHashMap<>();
            treeBody.put("base_tree", baseTree);
            treeBody.put("tree", entries);
            log.info("删除第 {} 批：{} 个文件（{} ~ {}）", done / batchFiles + 1, end - start, start + 1, end);
            String newTree = createObject(treeUrl(setting), headers(setting), Json.write(treeBody), "创建目录树");
            Map<String, Object> commitBody = new LinkedHashMap<>();
            commitBody.put("message", "chore: 清理重复文件 " + (end - start) + " 个");
            commitBody.put("tree", newTree);
            commitBody.put("parents", List.of(parent));
            parent = createObject(commitUrl(setting), headers(setting), Json.write(commitBody), "创建提交");
            baseTree = newTree;
            done += end - start;
            if (onBatchDone != null) {
                onBatchDone.accept(end - start);
            }
        }
        Map<String, Object> refBody = new LinkedHashMap<>();
        refBody.put("sha", parent);
        refBody.put("force", false);
        HttpSupport.patchJson(refsUrl(setting), headers(setting), Json.write(refBody)).requireOk("移动分支引用");
        log.info("清理完成：删除 {} 个文件，HEAD -> {}", done, parent);
        return done;
    }

    /**
     * 创建 git 对象（tree / commit）并校验返回的 sha。
     * 必须校验 sha 而不是只看状态码：GitHub 在数据量大时会返回 422 / 502，
     * 偶尔还会<b>返回 200 但 body 里没有 sha</b>，不校验就会拿空 sha 去建提交。
     */
    public String createObject(String url, Map<String, String> headers, String body, String action) {
        int maxRetry = Math.max(0, appProperties.getRepoMove().getRetryTimes());
        int attempt = 0;
        while (true) {
            attempt++;
            HttpResult result = HttpSupport.postJson(url, headers, body);
            String sha = "";
            String detail;
            if (result.isOk()) {
                try {
                    sha = Json.tree(result.body()).path("sha").asText("");
                } catch (RuntimeException e) {
                    sha = "";
                }
                detail = sha.length() == 40 ? "ok"
                        : "HTTP 200 但响应里没有有效的 sha：" + abbreviate(result.body());
            } else {
                detail = "HTTP " + result.status() + " " + abbreviate(result.body());
            }
            if (sha.length() == 40) {
                if (attempt > 1) {
                    log.info("{} 第 {} 次尝试成功", action, attempt);
                }
                return sha;
            }
            if (attempt > maxRetry) {
                throw new BizException(action + "失败：" + detail);
            }
            long wait = 3000L * attempt;
            log.warn("{} 第 {} 次重试（等待 {}ms）：{}", action, attempt, wait, detail);
            try {
                Thread.sleep(wait);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new BizException(action + "被中断");
            }
        }
    }

    public String headCommit(StorageSetting setting) {
        HttpResult result = HttpSupport.get(refUrl(setting), headers(setting));
        result.requireOk("读取分支信息");
        return Json.tree(result.body()).path("object").path("sha").asText();
    }

    public String commitTree(StorageSetting setting, String commitSha) {
        HttpResult result = HttpSupport.get(
                api(setting) + "/repos/" + setting.get("owner") + "/" + setting.get("repo")
                        + "/git/commits/" + commitSha, headers(setting));
        result.requireOk("读取提交信息");
        return Json.tree(result.body()).path("tree").path("sha").asText();
    }

    public String api(StorageSetting setting) {
        String base = setting.get("apiBase", DEFAULT_API);
        return StringUtils.hasText(base) ? base.replaceAll("/+$", "") : DEFAULT_API;
    }

    public String branch(StorageSetting setting) {
        String value = setting.get("branch", "main");
        return StringUtils.hasText(value) ? value : "main";
    }

    public Map<String, String> headers(StorageSetting setting) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer " + setting.get("token"));
        headers.put("Accept", "application/vnd.github+json");
        headers.put("X-GitHub-Api-Version", "2022-11-28");
        return headers;
    }

    public String treeUrl(StorageSetting setting) {
        return api(setting) + "/repos/" + setting.get("owner") + "/" + setting.get("repo") + "/git/trees";
    }

    public String commitUrl(StorageSetting setting) {
        return api(setting) + "/repos/" + setting.get("owner") + "/" + setting.get("repo") + "/git/commits";
    }

    /** 读取分支：/git/ref/heads/{branch}（单数） */
    public String refUrl(StorageSetting setting) {
        return api(setting) + "/repos/" + setting.get("owner") + "/" + setting.get("repo")
                + "/git/ref/heads/" + branch(setting);
    }

    /** 更新分支：/git/refs/heads/{branch}（复数，与读取不是同一个端点） */
    public String refsUrl(StorageSetting setting) {
        return api(setting) + "/repos/" + setting.get("owner") + "/" + setting.get("repo")
                + "/git/refs/heads/" + branch(setting);
    }

    private String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= 160 ? text : text.substring(0, 160) + "...";
    }
}
