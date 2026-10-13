package com.pichost.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.pichost.common.BizException;
import com.pichost.config.AppProperties;
import com.pichost.dto.RepoMoveRequest;
import com.pichost.dto.RepoMoveCheckVO;
import com.pichost.dto.RepoMoveResultVO;
import com.pichost.entity.ImageEntity;
import com.pichost.entity.StorageConfigEntity;
import com.pichost.mapper.ImageMapper;
import com.pichost.storage.StorageServiceFactory;
import com.pichost.storage.StorageSetting;
import com.pichost.storage.impl.GithubStorageService;
import com.pichost.storage.support.HttpSupport;
import com.pichost.storage.support.HttpSupport.HttpResult;
import com.pichost.storage.support.ImageTypes;
import com.pichost.storage.support.Json;
import com.pichost.storage.support.ObjectKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * 【临时功能】GitHub 仓库目录整理：把某个目录下的图片文件按系统存储规则搬到另一个目录。
 * <p>
 * 为什么不用通用的批量迁移：通用迁移是「逐文件下载 + 重新上传 + 删源」，
 * 4000 个文件 = 1.2 万次 API 调用，会撞上 5000 次/小时的配额，还会在仓库里留下 4000 个提交。
 * <p>
 * 同仓库移动其实不需要搬运内容：GitHub 的 Git Data API 允许新 tree 直接引用<b>原有 blob 的 SHA</b>，
 * 所以整个过程只要 5 ~ 6 次 API 调用、一个提交就能搬完任意数量的文件：
 * <pre>
 *   GET  /git/trees/{branch}?recursive=1   拿到所有文件路径 + blob SHA
 *   GET  /git/ref/heads/{branch}           拿到当前提交
 *   GET  /git/commits/{sha}                拿到当前 tree
 *   POST /git/trees                        新 tree：新路径引用旧 SHA，旧路径 sha=null（删除）
 *   POST /git/commits                      新提交
 *   PATCH /git/refs/heads/{branch}         把分支指过去
 * </pre>
 * 文件数量多时会分块提交，避免单个请求过大。
 * <p>
 * 注意：这会让仓库历史里出现一个新的提交（可以用 git revert 回退）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RepoMoveService {

    private static final String DEFAULT_API = "https://api.github.com";
    private static final int MAX_SAMPLES = 10;

    private final StorageConfigService storageConfigService;
    private final StorageServiceFactory storageServiceFactory;
    private final ShardAllocator shardAllocator;
    private final ImageMapper imageMapper;
    private final AppProperties appProperties;

    public RepoMoveResultVO preview(Long userId, RepoMoveRequest request) {
        request.setDryRun(true);
        return run(userId, request);
    }

    public RepoMoveResultVO execute(Long userId, RepoMoveRequest request) {
        request.setDryRun(false);
        return run(userId, request);
    }

    private RepoMoveResultVO run(Long userId, RepoMoveRequest request) {
        StorageConfigEntity config = storageConfigService.requireOwned(userId, request.getStorageConfigId());
        if (!"GITHUB".equals(config.getType())) {
            throw new BizException("这个工具只支持 GitHub 存储；其他类型请用「批量迁移」");
        }
        StorageSetting setting = storageConfigService.toSetting(config);
        String sourceDir = normalizeDir(request.getSourceDirectory(), setting);
        String targetDir = normalizeDir(request.getTargetDirectory(), setting);
        if (!StringUtils.hasText(sourceDir)) {
            throw new BizException("请填写源目录");
        }
        if (!StringUtils.hasText(targetDir)) {
            throw new BizException("请填写目标目录");
        }
        if (targetDir.startsWith(sourceDir) && !request.isIncludeSubdirectories()
                && targetDir.equals(sourceDir)) {
            throw new BizException("源目录与目标目录相同");
        }

        // 1. 拉整棵树
        CollectResult collected = collect(setting, sourceDir, targetDir, request, userId, config.getId());
        List<Moved> moved = collected.moved();

        RepoMoveResultVO result = new RepoMoveResultVO();
        result.setDryRun(request.isDryRun());
        result.setResolvedSourceDirectory(sourceDir);
        result.setResolvedTargetDirectory(targetDir);
        result.setTotal(moved.size());
        result.setRemaining(Math.max(0, collected.remaining()));
        result.setCollisions(collected.collisions());
        result.setCollisionSamples(collected.collisionSamples());
        if (moved.isEmpty()) {
            String reason = collected.collisions() > 0
                    ? "有 " + collected.collisions() + " 个文件因为目标位置已存在同名文件被跳过，其余没有可移动的图片"
                    : "在源目录 [" + sourceDir + "] 下没有找到符合条件的图片文件";
            if (!request.isDryRun()) {
                // 执行时绝不能静默成功 —— 否则用户看到"成功"却什么都没发生
                throw new BizException(reason
                        + "。解析出的源目录是 [" + sourceDir + "]，请确认它和仓库里的目录一致"
                        + "（不要带分支名，例如写成 api 而不是 main/api）");
            }
            result.setMessage(reason);
            return result;
        }
        result.setSamples(moved.stream().limit(MAX_SAMPLES)
                .map(item -> item.oldPath + "  ->  " + item.newPath).collect(Collectors.toList()));
        Map<String, Integer> shardStat = new TreeMap<>();
        for (Moved item : moved) {
            String key = shardOf(item.newPath, targetDir);
            shardStat.merge(key, 1, Integer::sum);
        }
        result.setShards(shardStat.entrySet().stream()
                .map(entry -> entry.getKey() + " -> " + entry.getValue() + " 个")
                .collect(Collectors.toList()));

        if (request.isDryRun()) {
            int batch = Math.max(1, appProperties.getRepoMove().getBatchFiles());
            int commitCount = (moved.size() + batch - 1) / batch;
            result.setMessage("预览：将移动 " + moved.size() + " 个文件到 " + targetDir
                    + "，每批 " + batch + " 个，预计 " + (commitCount * 2 + 3) + " 次 API 调用、"
                    + commitCount + " 个提交"
                    + (collected.collisions() > 0
                       ? "；另有 " + collected.collisions() + " 个因目标位置已存在同名文件会被跳过" : ""));
            return result;
        }

        // 2. 建 tree / commit，最后一次性移动分支引用
        String headSha = headCommit(setting);
        String baseTree = commitTree(setting, headSha);
        int batchFiles = Math.max(1, appProperties.getRepoMove().getBatchFiles());
        log.info("仓库目录整理：{} 个文件，每批 {} 个文件（{} 条 tree 记录）",
                moved.size(), batchFiles, batchFiles * 2);
        int commits = 0;
        String parent = headSha;
        String lastCommit = null;
        for (int start = 0; start < moved.size(); start += batchFiles) {
            int end = Math.min(start + batchFiles, moved.size());
            List<Map<String, Object>> entries = new ArrayList<>();
            for (Moved item : moved.subList(start, end)) {
                Map<String, Object> add = new HashMap<>();
                add.put("path", item.newPath);
                add.put("mode", "100644");
                add.put("type", "blob");
                add.put("sha", item.blobSha);
                entries.add(add);

                Map<String, Object> remove = new HashMap<>();
                remove.put("path", item.oldPath);
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
                    commits + 1, end - start, start + 1, end, treeJson.length() / 1024);
            String newTree = createObject(treeUrl(setting), headers(setting), treeJson, "创建目录树");

            String message = "chore: 整理图片目录 " + sourceDir + " -> " + targetDir
                    + "（" + (end - start) + " 个文件）";
            Map<String, Object> commitBody = new LinkedHashMap<>();
            commitBody.put("message", message);
            commitBody.put("tree", newTree);
            commitBody.put("parents", List.of(parent));
            parent = createObject(commitUrl(setting), headers(setting),
                    Json.write(commitBody), "创建提交");
            lastCommit = parent;
            baseTree = newTree;
            commits++;
        }

        Map<String, Object> refBody = new LinkedHashMap<>();
        refBody.put("sha", parent);
        refBody.put("force", false);
        // 注意：读取用 /git/ref/（单数），更新必须用 /git/refs/（复数），两个端点不一样
        HttpResult refResult = HttpSupport.patchJson(refsUrl(setting), headers(setting), Json.write(refBody));
        refResult.requireOk("移动分支引用");

        // 3. 同步数据库记录
        int updated = syncDatabase(userId, config, setting, moved);

        result.setMoved(moved.size());
        result.setCommits(commits);
        result.setCommitSha(lastCommit);
        result.setCommitUrl("https://github.com/" + setting.get("owner") + "/" + setting.get("repo")
                + "/commit/" + lastCommit);
        result.setMessage("已移动 " + moved.size() + " 个文件，共 " + commits + " 个提交，"
                + updated + " 条图库记录已同步更新"
                + (result.getRemaining() > 0 ? "；源目录还有 " + result.getRemaining() + " 个待搬" : "；源目录已清空"));
        log.info("仓库目录整理完成：{} 个文件，{} 个提交，更新 {} 条记录", moved.size(), commits, updated);
        return result;
    }

    /**
     * 自检：把这条链路上每一环都单独验一遍，用于排查"执行了但没动静"这类静默问题。
     * 全部只读，不会对仓库做任何修改。
     */
    public List<RepoMoveCheckVO> diagnose(Long userId, RepoMoveRequest request) {
        List<RepoMoveCheckVO> checks = new ArrayList<>();
        StorageConfigEntity config;
        try {
            config = storageConfigService.requireOwned(userId, request.getStorageConfigId());
        } catch (RuntimeException e) {
            checks.add(RepoMoveCheckVO.of("存储配置", false, e.getMessage()));
            return checks;
        }
        checks.add(RepoMoveCheckVO.of("存储配置", "GITHUB".equals(config.getType()),
                config.getName() + " / " + config.getType()));
        if (!"GITHUB".equals(config.getType())) {
            return checks;
        }

        StorageSetting setting = storageConfigService.toSetting(config);
        String sourceDir = normalizeDir(request.getSourceDirectory(), setting);
        String targetDir = normalizeDir(request.getTargetDirectory(), setting);
        checks.add(RepoMoveCheckVO.of("解析后的目录", true,
                "源 = [" + sourceDir + "]  目标 = [" + targetDir + "]"
                        + "  包含子目录 = " + request.isIncludeSubdirectories()
                        + "  保留原名 = " + request.isKeepFileName()
                        + "  分层 = " + request.isLayered()));

        try {
            HttpResult user = HttpSupport.get(api(setting) + "/user", headers(setting));
            checks.add(RepoMoveCheckVO.of("Token 有效性", user.isOk(),
                    user.isOk() ? "登录身份 " + Json.tree(user.body()).path("login").asText()
                                : "HTTP " + user.status() + " " + abbreviate(user.body())));
        } catch (RuntimeException e) {
            checks.add(RepoMoveCheckVO.of("Token 有效性", false, e.getMessage()));
        }

        try {
            HttpResult repo = HttpSupport.get(api(setting) + "/repos/" + setting.get("owner")
                    + "/" + setting.get("repo"), headers(setting));
            if (repo.isOk()) {
                JsonNode body = Json.tree(repo.body());
                boolean push = body.path("permissions").path("push").asBoolean(false);
                checks.add(RepoMoveCheckVO.of("仓库写权限", push,
                        (push ? "有 push 权限" : "没有 push 权限，无法创建提交")
                                + "  默认分支 = " + body.path("default_branch").asText()
                                + "  私有 = " + body.path("private").asBoolean(false)));
            } else {
                checks.add(RepoMoveCheckVO.of("仓库写权限", false,
                        "HTTP " + repo.status() + " " + abbreviate(repo.body())));
            }
        } catch (RuntimeException e) {
            checks.add(RepoMoveCheckVO.of("仓库写权限", false, e.getMessage()));
        }

        try {
            HttpResult ref = HttpSupport.get(refUrl(setting), headers(setting));
            if (ref.isOk()) {
                String head = Json.tree(ref.body()).path("object").path("sha").asText();
                checks.add(RepoMoveCheckVO.of("分支 " + branch(setting), true, "HEAD = " + abbreviateSha(head)));
            } else {
                checks.add(RepoMoveCheckVO.of("分支 " + branch(setting), false,
                        "HTTP " + ref.status() + " " + abbreviate(ref.body())));
            }
        } catch (RuntimeException e) {
            checks.add(RepoMoveCheckVO.of("分支 " + branch(setting), false, e.getMessage()));
        }

        try {
            HttpResult commits = HttpSupport.get(api(setting) + "/repos/" + setting.get("owner")
                    + "/" + setting.get("repo") + "/commits?per_page=5", headers(setting));
            if (commits.isOk()) {
                StringBuilder text = new StringBuilder();
                boolean mine = false;
                for (JsonNode node : Json.tree(commits.body())) {
                    String message = node.path("commit").path("message").asText("").split("\\n")[0];
                    if (message.startsWith("chore: 整理图片目录")) {
                        mine = true;
                    }
                    text.append(abbreviateSha(node.path("sha").asText())).append(' ').append(message).append(" | ");
                }
                checks.add(RepoMoveCheckVO.of("最近提交", true,
                        (mine ? "发现本工具创建的提交；" : "没发现本工具创建的提交；") + text));
            } else {
                checks.add(RepoMoveCheckVO.of("最近提交", false,
                        "HTTP " + commits.status() + " " + abbreviate(commits.body())));
            }
        } catch (RuntimeException e) {
            checks.add(RepoMoveCheckVO.of("最近提交", false, e.getMessage()));
        }

        try {
            HttpResult tree = HttpSupport.get(api(setting) + "/repos/" + setting.get("owner")
                    + "/" + setting.get("repo") + "/git/trees/" + branch(setting) + "?recursive=1",
                    headers(setting));
            if (!tree.isOk()) {
                checks.add(RepoMoveCheckVO.of("文件树", false,
                        "HTTP " + tree.status() + " " + abbreviate(tree.body())));
            } else {
                JsonNode root = Json.tree(tree.body());
                boolean truncated = root.path("truncated").asBoolean(false);
                int total = 0;
                int underSource = 0;
                int matched = 0;
                for (JsonNode node : root.path("tree")) {
                    if (!"blob".equals(node.path("type").asText())) {
                        continue;
                    }
                    total++;
                    String path = node.path("path").asText();
                    if (path.startsWith(sourceDir)) {
                        underSource++;
                        String rest = path.substring(sourceDir.length());
                        if (rest.isEmpty()) {
                            continue;
                        }
                        if (!request.isIncludeSubdirectories() && rest.contains("/")) {
                            continue;
                        }
                        if (ImageTypes.isImage(rest)) {
                            matched++;
                        }
                    }
                }
                checks.add(RepoMoveCheckVO.of("文件树", !truncated,
                        "文件总数 " + total + "  截断 = " + truncated
                                + "  以 [" + sourceDir + "] 开头的 " + underSource
                                + "  其中符合条件的图片 " + matched));
            }
        } catch (RuntimeException e) {
            checks.add(RepoMoveCheckVO.of("文件树", false, e.getMessage()));
        }

        try {
            HttpResult limit = HttpSupport.get(api(setting) + "/rate_limit", headers(setting));
            if (limit.isOk()) {
                JsonNode core = Json.tree(limit.body()).path("resources").path("core");
                checks.add(RepoMoveCheckVO.of("API 配额", true,
                        "剩余 " + core.path("remaining").asLong() + " / " + core.path("limit").asLong()));
            }
        } catch (RuntimeException e) {
            checks.add(RepoMoveCheckVO.of("API 配额", false, e.getMessage()));
        }

        try {
            CollectResult collected = collect(setting, sourceDir, targetDir, request, userId, config.getId());
            checks.add(RepoMoveCheckVO.of("本次将移动", !collected.moved().isEmpty(),
                    "可移动 " + collected.moved().size() + " 个，重名跳过 " + collected.collisions() + " 个"
                            + (collected.moved().isEmpty() ? "" : "；示例 " + collected.moved().get(0).oldPath()
                            + " -> " + collected.moved().get(0).newPath())));
        } catch (RuntimeException e) {
            checks.add(RepoMoveCheckVO.of("本次将移动", false, e.getMessage()));
        }
        return checks;
    }

    private String abbreviateSha(String sha) {
        return sha == null || sha.length() < 8 ? String.valueOf(sha) : sha.substring(0, 8);
    }

    private String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= 160 ? text : text.substring(0, 160) + "...";
    }

    // ================================================================ 收集与过滤

    private CollectResult collect(StorageSetting setting, String sourceDir, String targetDir,
                                  RepoMoveRequest request, Long userId, Long configId) {
        HttpResult treeResult = HttpSupport.get(
                api(setting) + "/repos/" + setting.get("owner") + "/" + setting.get("repo")
                        + "/git/trees/" + branch(setting) + "?recursive=1", headers(setting));
        treeResult.requireOk("读取仓库文件树");
        JsonNode root = Json.tree(treeResult.body());
        if (root.path("truncated").asBoolean(false)) {
            throw new BizException("仓库文件树被 GitHub 截断了（仓库过大），请改用「批量迁移」按目录分批处理");
        }

        // 仓库里已存在的所有路径：保留原文件名时用来检测"目标位置已有同名文件"
        Set<String> existing = new HashSet<>();
        for (JsonNode node : root.path("tree")) {
            if ("blob".equals(node.path("type").asText())) {
                existing.add(node.path("path").asText());
            }
        }

        // 先挑出候选文件，再统一规划分片号：
        // 预览只"看"不占号，执行才真正分配，否则预览一次分片号就跳了。
        List<String[]> candidates = new ArrayList<>();
        for (JsonNode node : root.path("tree")) {
            if (!"blob".equals(node.path("type").asText())) {
                continue;
            }
            String path = node.path("path").asText();
            if (!path.startsWith(sourceDir)) {
                continue;
            }
            String rest = path.substring(sourceDir.length());
            if (rest.isEmpty()) {
                continue;
            }
            if (!request.isIncludeSubdirectories() && rest.contains("/")) {
                continue;
            }
            if (!ImageTypes.isImage(rest)) {
                continue;
            }
            candidates.add(new String[]{path, rest, node.path("sha").asText()});
        }
        if (candidates.isEmpty()) {
            return new CollectResult(List.of(), 0, List.of());
        }
        // 跳过前 N 个（整理分片：前 N 个留在原地）
        int total = candidates.size();
        if (request.getSkipFirst() > 0) {
            if (candidates.size() <= request.getSkipFirst()) {
                return new CollectResult(List.of(), 0, List.of(), 0);
            }
            candidates = new ArrayList<>(candidates.subList(request.getSkipFirst(), candidates.size()));
        }
        // 分批执行：本次只处理前 maxFiles 个，剩下的下次再搬。
        // 搬走的文件不再匹配源目录，所以反复执行会自然推进，不需要额外记录进度。
        int remaining = 0;
        if (request.getMaxFiles() > 0 && candidates.size() > request.getMaxFiles()) {
            remaining = total - request.getSkipFirst() - request.getMaxFiles();
            candidates = new ArrayList<>(candidates.subList(0, request.getMaxFiles()));
        }

        boolean layered = request.isLayered();
        List<Integer> shards = layered
                ? (request.isDryRun()
                        ? shardAllocator.planShards(userId, configId, targetDir, candidates.size())
                        : allocate(userId, configId, targetDir, candidates.size()))
                : Collections.nCopies(candidates.size(), 0);

        Set<String> planned = new HashSet<>();
        List<Moved> moved = new ArrayList<>(candidates.size());
        List<String> collisions = new ArrayList<>();
        int collisionCount = 0;
        for (int i = 0; i < candidates.size(); i++) {
            String[] item = candidates.get(i);
            String sourceName = item[1].substring(item[1].lastIndexOf('/') + 1);
            String fileName = request.isKeepFileName() ? sourceName : ObjectKeys.randomFileName(sourceName);
            String newPath = composePath(targetDir, fileName, layered, shards.get(i));
            // 保留原文件名就可能撞上同名的已有文件。宁可跳过并报出来，也不静默覆盖别人的文件。
            if (existing.contains(newPath) || !planned.add(newPath)) {
                collisionCount++;
                if (collisions.size() < MAX_SAMPLES) {
                    collisions.add(item[0] + " -> " + newPath);
                }
                continue;
            }
            moved.add(new Moved(item[0], newPath, item[2]));
        }
        return new CollectResult(moved, collisionCount, collisions, remaining);
    }

    /** 目标对象键：可选 日期/分片 层级，文件名按需保留或重命名 */
    private String composePath(String targetDir, String fileName, boolean layered, int shard) {
        if (!layered) {
            // 只换目录、不改名：{目标目录}/{原文件名}
            return targetDir + fileName;
        }
        String shardSegment = shard > 0 ? String.format("%03d/", shard) : "";
        return ObjectKeys.dayDirectory(targetDir) + shardSegment + fileName;
    }

    /** 真正占用 count 个分片号 */
    private List<Integer> allocate(Long userId, Long configId, String targetDir, int count) {
        List<Integer> shards = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            shards.add(shardAllocator.nextShard(userId, configId, targetDir));
        }
        return shards;
    }

    /** 收集结果：可移动的文件 + 因重名被跳过的统计 */
    private record CollectResult(List<Moved> moved, int collisions, List<String> collisionSamples,
                                 int remaining) {

        CollectResult(List<Moved> moved, int collisions, List<String> collisionSamples) {
            this(moved, collisions, collisionSamples, 0);
        }
    }

    private int syncDatabase(Long userId, StorageConfigEntity config, StorageSetting setting, List<Moved> moved) {
        List<ImageEntity> rows = imageMapper.selectList(Wrappers.<ImageEntity>lambdaQuery()
                .eq(ImageEntity::getUserId, userId)
                .eq(ImageEntity::getStorageConfigId, config.getId()));
        Map<String, ImageEntity> byKey = new HashMap<>();
        for (ImageEntity row : rows) {
            byKey.putIfAbsent(row.getObjectKey(), row);
        }
        GithubStorageService service = (GithubStorageService) storageServiceFactory.get(setting.type());
        int updated = 0;
        for (Moved item : moved) {
            ImageEntity row = byKey.get(item.oldPath);
            if (row == null) {
                continue;
            }
            ImageEntity update = new ImageEntity();
            update.setId(row.getId());
            update.setObjectKey(item.newPath);
            update.setUrl(service.publicUrl(setting, item.newPath));
            update.setThumbUrl(null);
            imageMapper.updateById(update);
            updated++;
        }
        return updated;
    }

    private record Moved(String oldPath, String newPath, String blobSha) {
    }

    // ================================================================ GitHub 调用

    /**
     * 创建 git 对象（tree / commit）并校验返回的 sha。
     * <p>
     * 这里必须校验 sha，而不是只看 HTTP 状态码：GitHub 在 tree 太大时有三种表现 ——
     * 422 "request timed out"、502 Server Error、以及最阴的<b>返回 200 但 body 里没有 sha</b>。
     * 第三种如果不校验，就会拿一个空 sha 去建提交，报出 "Tree SHA does not exist" 这种看不懂的错误。
     * <p>
     * 失败不会留下痕迹：tree / commit 在更新分支引用之前都是悬空对象，仓库内容不受影响。
     */
    private String createObject(String url, Map<String, String> headers, String body, String action) {
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
                detail = sha.length() == 40
                        ? "ok"
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

    private String headCommit(StorageSetting setting) {
        HttpResult result = HttpSupport.get(refUrl(setting), headers(setting));
        result.requireOk("读取分支信息");
        return Json.tree(result.body()).path("object").path("sha").asText();
    }

    private String commitTree(StorageSetting setting, String commitSha) {
        HttpResult result = HttpSupport.get(
                api(setting) + "/repos/" + setting.get("owner") + "/" + setting.get("repo")
                        + "/git/commits/" + commitSha, headers(setting));
        result.requireOk("读取提交信息");
        return Json.tree(result.body()).path("tree").path("sha").asText();
    }

    private String api(StorageSetting setting) {
        String base = setting.get("apiBase", DEFAULT_API);
        return StringUtils.hasText(base) ? base.replaceAll("/+$", "") : DEFAULT_API;
    }

    private String branch(StorageSetting setting) {
        String branch = setting.get("branch", "main");
        return StringUtils.hasText(branch) ? branch : "main";
    }

    private String treeUrl(StorageSetting setting) {
        return api(setting) + "/repos/" + setting.get("owner") + "/" + setting.get("repo") + "/git/trees";
    }

    private String commitUrl(StorageSetting setting) {
        return api(setting) + "/repos/" + setting.get("owner") + "/" + setting.get("repo") + "/git/commits";
    }

    /** 读取分支：/git/ref/heads/{branch}（单数） */
    private String refUrl(StorageSetting setting) {
        return api(setting) + "/repos/" + setting.get("owner") + "/" + setting.get("repo")
                + "/git/ref/heads/" + branch(setting);
    }

    /** 更新分支：/git/refs/heads/{branch}（复数，与读取不是同一个端点） */
    private String refsUrl(StorageSetting setting) {
        return api(setting) + "/repos/" + setting.get("owner") + "/" + setting.get("repo")
                + "/git/refs/heads/" + branch(setting);
    }

    private Map<String, String> headers(StorageSetting setting) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer " + setting.get("token"));
        headers.put("Accept", "application/vnd.github+json");
        headers.put("X-GitHub-Api-Version", "2022-11-28");
        return headers;
    }

    /**
     * 目录归一化。用户很容易把分支名或仓库名一起粘进来，这里都容错掉：
     * <ul>
     *   <li>@@main/api@@ —— 去掉分支名前缀</li>
     *   <li>@@api/images/@@ —— 去掉多余的斜杠</li>
     *   <li>@@qxFly/qxfly-image/main/api@@ —— 去掉 owner/repo 与分支</li>
     *   <li>@@https://github.com/qxFly/qxfly-image/tree/main/api@@ —— 解析完整 URL</li>
     * </ul>
     */
    private String normalizeDir(String dir, StorageSetting setting) {
        if (!StringUtils.hasText(dir)) {
            return "";
        }
        String value = dir.trim();

        int github = value.indexOf("github.com/");
        if (github >= 0) {
            String[] parts = value.substring(github + "github.com/".length()).split("/");
            // owner/repo/(tree|blob)/branch/后续路径
            if (parts.length > 4 && ("tree".equals(parts[2]) || "blob".equals(parts[2]))) {
                value = String.join("/", java.util.Arrays.copyOfRange(parts, 4, parts.length));
            } else if (parts.length > 2) {
                value = String.join("/", java.util.Arrays.copyOfRange(parts, 2, parts.length));
            }
        }

        value = value.replaceAll("^/+", "");
        String ownerRepo = setting.get("owner") + "/" + setting.get("repo") + "/";
        if (value.startsWith(ownerRepo)) {
            value = value.substring(ownerRepo.length());
        }
        String branchPrefix = branch(setting) + "/";
        if (value.startsWith(branchPrefix)) {
            value = value.substring(branchPrefix.length());
        }
        value = value.replaceAll("^/+", "");
        if (value.isEmpty()) {
            return "";
        }
        return value.endsWith("/") ? value : value + "/";
    }

    /** 取出"日期/分片"这段目录，用于展示分布 */
    private String shardOf(String path, String targetDir) {
        String rest = path.substring(targetDir.length());
        int slash = rest.lastIndexOf('/');
        return slash < 0 ? "(无分片)" : rest.substring(0, slash);
    }
}
