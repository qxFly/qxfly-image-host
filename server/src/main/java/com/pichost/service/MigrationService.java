package com.pichost.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.pichost.common.BizException;
import com.pichost.config.AppProperties;
import com.pichost.dto.MigrateRequest;
import com.pichost.dto.MigrationErrorVO;
import com.pichost.dto.MigrationTaskVO;
import com.pichost.entity.ImageEntity;
import com.pichost.entity.StorageConfigEntity;
import com.pichost.mapper.ImageMapper;
import com.pichost.storage.StorageObject;
import com.pichost.storage.StorageService;
import com.pichost.storage.StorageServiceFactory;
import com.pichost.storage.StorageSetting;
import com.pichost.storage.StorageType;
import com.pichost.storage.UploadResult;
import com.pichost.storage.support.HttpSupport;
import com.pichost.storage.support.ObjectKeys;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * 图库批量迁移：把已保存的图片搬到另一个路径前缀或另一个存储后端。
 * <p>
 * 设计要点：
 * <ul>
 *   <li>后台任务：一次可以搬几万张，接口立刻返回 taskId，前端轮询进度</li>
 *   <li>文件级并发：每张图要「下载 + 上传」两次网络往返，串行会慢得离谱，
 *       所以用线程池并行处理，并发数由 app.migrate.concurrency 控制</li>
 *   <li>限流重试：命中 403/429/5xx/超时 时退避重试，避免撞上 GitHub 的配额直接失败</li>
 *   <li>分块提交：每处理一批再提交下一批，这样「取消」能在一批之内生效</li>
 *   <li>任务只存在内存里，最多保留每个用户最近 50 条；服务重启后列表清空（图片记录不受影响）</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MigrationService {

    /** 每种失败原因最多保留多少个示例文件名（计数仍是完整的） */
    private static final int MAX_SAMPLES_PER_REASON = 10;
    private static final int MAX_TASKS_PER_USER = 50;

    private final ImageMapper imageMapper;
    private final StorageConfigService storageConfigService;
    private final StorageServiceFactory storageServiceFactory;
    private final ShardAllocator shardAllocator;
    private final GithubTreeMover treeMover;
    private final AppProperties appProperties;

    private final Map<String, MigrationTask> tasks = new ConcurrentHashMap<>();

    /** 负责编排任务本身（每个任务占一个线程） */
    private final ExecutorService taskExecutor = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "pichost-migrate-task");
        thread.setDaemon(true);
        return thread;
    });

    /** 负责单张图片的下载/上传，大小 = 配置的并发数 */
    private volatile ExecutorService fileExecutor;

    @PreDestroy
    public void shutdown() {
        taskExecutor.shutdownNow();
        ExecutorService pool = fileExecutor;
        if (pool != null) {
            pool.shutdownNow();
        }
    }

    private ExecutorService filePool() {
        ExecutorService pool = fileExecutor;
        if (pool == null) {
            synchronized (this) {
                if (fileExecutor == null) {
                    int size = Math.max(1, appProperties.getMigrate().getConcurrency());
                    fileExecutor = Executors.newFixedThreadPool(size, runnable -> {
                        Thread thread = new Thread(runnable, "pichost-migrate-io");
                        thread.setDaemon(true);
                        return thread;
                    });
                    log.info("迁移文件级线程池已创建，并发数 = {}", size);
                }
                pool = fileExecutor;
            }
        }
        return pool;
    }

    // ================================================================ 对外接口

    public MigrationTaskVO start(Long userId, MigrateRequest request) {
        StorageConfigEntity target = storageConfigService.requireOwned(userId, request.getTargetStorageConfigId());
        List<ImageEntity> images = resolveImages(userId, request);
        if (images.isEmpty()) {
            throw new BizException("没有匹配到需要迁移的图片");
        }
        String sourceName = "全部来源";
        if (request.getSourceStorageConfigId() != null) {
            try {
                sourceName = storageConfigService
                        .requireOwned(userId, request.getSourceStorageConfigId()).getName();
            } catch (RuntimeException ignored) {
                sourceName = "未知来源";
            }
        }
        MigrationTask task = new MigrationTask(UUID.randomUUID().toString().replace("-", ""),
                userId, images.size(), sourceName, target.getName(),
                Math.max(1, appProperties.getMigrate().getConcurrency()));
        tasks.put(task.id, task);
        pruneTasks(userId);
        taskExecutor.submit(() -> run(task, userId, request, target, images));
        log.info("迁移任务 {} 已创建：{} 张，来源 {} -> {}，并发 {}",
                task.id, images.size(), sourceName, target.getName(), task.concurrency);
        return toVO(task);
    }

    /** 当前用户的迁移任务列表（新的在前），用于后台任务面板 */
    public List<MigrationTaskVO> list(Long userId) {
        return tasks.values().stream()
                .filter(task -> task.userId.equals(userId))
                .sorted(Comparator.comparingLong((MigrationTask task) -> task.startedAt).reversed())
                .map(this::toVO)
                .collect(Collectors.toList());
    }

    public MigrationTaskVO status(Long userId, String taskId) {
        return toVO(requireTask(userId, taskId));
    }

    public MigrationTaskVO cancel(Long userId, String taskId) {
        MigrationTask task = requireTask(userId, taskId);
        task.cancelled = true;
        return toVO(task);
    }

    private MigrationTask requireTask(Long userId, String taskId) {
        MigrationTask task = tasks.get(taskId);
        if (task == null || !task.userId.equals(userId)) {
            throw BizException.notFound("迁移任务不存在或已过期");
        }
        return task;
    }

    /** 只保留每个用户最近 N 条，优先淘汰已结束的 */
    private void pruneTasks(Long userId) {
        List<MigrationTask> mine = tasks.values().stream()
                .filter(task -> task.userId.equals(userId))
                .sorted(Comparator.comparingLong(task -> task.startedAt))
                .collect(Collectors.toList());
        int overflow = mine.size() - MAX_TASKS_PER_USER;
        for (MigrationTask task : mine) {
            if (overflow <= 0) {
                break;
            }
            if (isFinished(task.status)) {
                tasks.remove(task.id);
                overflow--;
            }
        }
    }

    private boolean isFinished(String status) {
        return !"PENDING".equals(status) && !"RUNNING".equals(status);
    }

    // ================================================================ 执行

    private void run(MigrationTask task, Long userId, MigrateRequest request,
                     StorageConfigEntity targetConfig, List<ImageEntity> images) {
        task.status = "RUNNING";
        StorageSetting targetSetting = storageConfigService.toSetting(targetConfig);
        StorageService targetService = storageServiceFactory.get(targetSetting.type());
        KeyBuilder keyBuilder = new KeyBuilder(userId, targetConfig.getId(), targetSetting,
                request.getTargetPrefix(), request.isPreserveDirectory(), request.isPreserveFileName());

        // 同仓库内换目录：不走「下载 + 上传 + 删源」，而是直接引用仓库里原有的 blob。
        // 既不消耗流量、不会产生重复文件，几千个文件也只要几次 API 调用。
        StorageSetting sameRepoSource = sameRepoSource(userId, targetConfig, targetSetting, images);
        if (sameRepoSource != null) {
            runQuickMove(task, userId, targetConfig, targetSetting, sameRepoSource, images, keyBuilder);
            return;
        }

        Map<Long, StorageConfigEntity> sourceCache = new ConcurrentHashMap<>();

        int concurrency = Math.max(1, appProperties.getMigrate().getConcurrency());
        int batchSize = Math.max(concurrency * 4, 16);

        try {
            for (int start = 0; start < images.size() && !task.cancelled; start += batchSize) {
                int end = Math.min(start + batchSize, images.size());
                List<Future<?>> futures = new ArrayList<>(end - start);
                for (ImageEntity image : images.subList(start, end)) {
                    if (task.cancelled) {
                        break;
                    }
                    futures.add(filePool().submit(() -> processImage(
                            task, userId, image, request, targetConfig, targetSetting,
                            targetService, keyBuilder, sourceCache)));
                }
                for (Future<?> future : futures) {
                    try {
                        future.get();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        task.cancelled = true;
                    } catch (Exception e) {
                        log.debug("迁移子任务异常: {}", e.getMessage());
                    }
                }
            }
            if (task.cancelled) {
                task.status = "CANCELLED";
            } else if (task.failed.get() == 0) {
                task.status = "SUCCESS";
            } else if (task.succeeded.get() > 0) {
                task.status = "PARTIAL";
            } else {
                task.status = "FAILED";
            }
        } catch (Exception e) {
            task.status = "FAILED";
            task.message = e.getMessage();
            log.error("迁移任务 {} 异常终止", task.id, e);
        } finally {
            task.finishedAt = System.currentTimeMillis();
            task.currentFile = null;
            log.info("迁移任务 {} 结束：状态 {}，成功 {}，失败 {}，跳过 {}，耗时 {} ms",
                    task.id, task.status, task.succeeded.get(), task.failed.get(), task.skipped.get(),
                    task.finishedAt - task.startedAt);
        }
    }

    /**
     * 判断能否走「引用式搬移」：所有图片都来自同一个存储配置，且它与目标指向同一个 GitHub 仓库。
     *
     * @return 源存储设置；不能走引用式搬移时返回 null
     */
    private StorageSetting sameRepoSource(Long userId, StorageConfigEntity targetConfig,
                                          StorageSetting targetSetting, List<ImageEntity> images) {
        if (images.isEmpty() || targetSetting.type() != StorageType.GITHUB) {
            return null;
        }
        Long sourceId = null;
        for (ImageEntity image : images) {
            if (sourceId == null) {
                sourceId = image.getStorageConfigId();
            } else if (!sourceId.equals(image.getStorageConfigId())) {
                // 来源不止一个存储，没法一次引用搬完
                return null;
            }
        }
        if (sourceId == null) {
            return null;
        }
        StorageConfigEntity sourceConfig;
        try {
            sourceConfig = storageConfigService.requireOwned(userId, sourceId);
        } catch (RuntimeException e) {
            return null;
        }
        StorageSetting sourceSetting = storageConfigService.toSetting(sourceConfig);
        return sameRepo(sourceSetting, targetSetting) ? sourceSetting : null;
    }

    private boolean sameRepo(StorageSetting a, StorageSetting b) {
        return a.type() == StorageType.GITHUB && b.type() == StorageType.GITHUB
                && Objects.equals(a.get("owner"), b.get("owner"))
                && Objects.equals(a.get("repo"), b.get("repo"))
                && Objects.equals(nvl(a.get("branch"), "main"), nvl(b.get("branch"), "main"))
                && "contents".equalsIgnoreCase(nvl(a.get("mode"), "contents"))
                && "contents".equalsIgnoreCase(nvl(b.get("mode"), "contents"));
    }

    private String nvl(String value, String fallback) {
        return StringUtils.hasText(value) ? value : fallback;
    }

    /**
     * 引用式搬移：同仓库换目录时直接复用原有 blob，不下载也不上传。
     * 进度按批次上报，最后统一同步数据库。
     */
    private void runQuickMove(MigrationTask task, Long userId, StorageConfigEntity targetConfig,
                              StorageSetting targetSetting, StorageSetting sourceSetting,
                              List<ImageEntity> images, KeyBuilder keyBuilder) {
        log.info("迁移任务 {} 走同仓库引用式搬移：{} 个文件", task.id, images.size());
        try {
            GithubTreeMover.Snapshot snapshot = treeMover.snapshot(targetSetting);
            List<GithubTreeMover.Item> items = new ArrayList<>();
            Map<String, ImageEntity> byKey = new HashMap<>();
            Set<String> planned = new HashSet<>();
            for (ImageEntity image : images) {
                if (task.cancelled) {
                    break;
                }
                task.currentFile = image.getOriginalName();
                String oldPath = image.getObjectKey();
                String sha = snapshot.blobShas().get(oldPath);
                if (sha == null) {
                    task.failed.incrementAndGet();
                    task.done.incrementAndGet();
                    task.recordError(image.getOriginalName(), "仓库里找不到该文件（可能已被移动或删除）");
                    continue;
                }
                String newKey = keyBuilder.next(image, sourceSetting, task);
                if (newKey.equals(oldPath)) {
                    task.skipped.incrementAndGet();
                    task.done.incrementAndGet();
                    continue;
                }
                if (!planned.add(newKey)) {
                    task.skipped.incrementAndGet();
                    task.done.incrementAndGet();
                    task.recordError(image.getOriginalName(), "目标路径与本次迁移中的其它文件重复");
                    continue;
                }
                items.add(new GithubTreeMover.Item(oldPath, newKey, sha));
                byKey.put(oldPath, image);
            }
            if (!items.isEmpty()) {
                List<GithubTreeMover.Item> moved = treeMover.move(targetSetting, items,
                        count -> task.done.addAndGet(count));
                StorageService targetService = storageServiceFactory.get(targetSetting.type());
                int updated = 0;
                for (GithubTreeMover.Item item : moved) {
                    ImageEntity image = byKey.get(item.oldPath());
                    if (image == null) {
                        continue;
                    }
                    ImageEntity update = new ImageEntity();
                    update.setId(image.getId());
                    update.setObjectKey(item.newPath());
                    update.setUrl(targetService.publicUrl(targetSetting, item.newPath()));
                    update.setThumbUrl(null);
                    imageMapper.updateById(update);
                    updated++;
                }
                task.succeeded.addAndGet(moved.size());
                log.info("引用式搬移完成：移动 {} 个，数据库更新 {} 条", moved.size(), updated);
            }
            if (task.cancelled) {
                task.status = "CANCELLED";
            } else {
                task.status = task.failed.get() > 0 ? "PARTIAL" : "SUCCESS";
            }
        } catch (Exception e) {
            task.status = "FAILED";
            task.message = e.getMessage();
            log.error("引用式搬移失败", e);
        } finally {
            task.finishedAt = System.currentTimeMillis();
            task.currentFile = null;
        }
    }

    private void processImage(MigrationTask task, Long userId, ImageEntity image, MigrateRequest request,
                              StorageConfigEntity targetConfig, StorageSetting targetSetting,
                              StorageService targetService, KeyBuilder keyBuilder,
                              Map<Long, StorageConfigEntity> sourceCache) {
        if (task.cancelled) {
            return;
        }
        task.currentFile = image.getOriginalName();
        try {
            StorageConfigEntity sourceConfig = sourceCache.computeIfAbsent(image.getStorageConfigId(),
                    id -> storageConfigService.requireOwned(userId, id));
            StorageSetting sourceSetting = storageConfigService.toSetting(sourceConfig);

            byte[] content = withRetry("下载 " + image.getObjectKey(),
                    () -> fetchContent(image, sourceSetting));
            String newKey = keyBuilder.next(image, sourceSetting, task);

            boolean sameObject = targetConfig.getId().equals(image.getStorageConfigId())
                    && newKey.equals(image.getObjectKey());
            if (sameObject) {
                task.skipped.incrementAndGet();
                return;
            }

            UploadResult uploaded = withRetry("上传 " + newKey,
                    () -> targetService.upload(targetSetting,
                            new StorageObject(newKey, image.getOriginalName(), image.getMimeType(), content)));

            ImageEntity update = new ImageEntity();
            update.setId(image.getId());
            update.setStorageConfigId(targetConfig.getId());
            update.setStorageType(targetConfig.getType());
            update.setObjectKey(uploaded.objectKey());
            update.setUrl(uploaded.url());
            update.setThumbUrl(uploaded.thumbUrl());
            update.setSize((long) content.length);
            imageMapper.updateById(update);

            // 能走到这里说明新对象键一定与旧的不同（相同的在上面已经 skip 掉了），
            // 所以"同存储内换目录"也必须删源文件，否则会在仓库里留下一份重复文件。
            if (request.isDeleteSource()) {
                deleteSourceQuietly(task, image, sourceSetting);
            }
            task.succeeded.incrementAndGet();
        } catch (Exception e) {
            task.failed.incrementAndGet();
            task.recordError(image.getOriginalName(), e.getMessage());
            log.debug("迁移失败 id={} key={}: {}", image.getId(), image.getObjectKey(), e.getMessage());
        } finally {
            task.done.incrementAndGet();
        }
    }

    /** 限流 / 5xx / 超时 时退避重试，避免撞上配额直接判失败 */
    private <T> T withRetry(String what, Supplier<T> action) {
        int maxRetry = Math.max(0, appProperties.getMigrate().getRetryTimes());
        int attempt = 0;
        while (true) {
            try {
                return action.get();
            } catch (RuntimeException e) {
                attempt++;
                if (attempt > maxRetry || !isRetryable(e)) {
                    throw e;
                }
                long wait = 800L * attempt;
                log.warn("{} 第 {} 次重试（等待 {}ms）：{}", what, attempt, wait, e.getMessage());
                try {
                    Thread.sleep(wait);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }

    private boolean isRetryable(Throwable e) {
        String message = e.getMessage();
        if (message == null) {
            return false;
        }
        String text = message.toLowerCase(Locale.ROOT);
        return text.contains("rate limit")
                || text.contains("secondary rate")
                || text.contains("http 403")
                || text.contains("http 429")
                || text.contains("http 500")
                || text.contains("http 502")
                || text.contains("http 503")
                || text.contains("http 504")
                || text.contains("timed out")
                || text.contains("timeout")
                || text.contains("connection reset");
    }

    /** 优先按对象键下载（私有仓库/私有桶也能用），失败再回退到已保存的直链 */
    private byte[] fetchContent(ImageEntity image, StorageSetting sourceSetting) {
        StorageService sourceService = storageServiceFactory.get(sourceSetting.type());
        if (sourceService.supportsDownload() && StringUtils.hasText(image.getObjectKey())) {
            try {
                byte[] content = sourceService.download(sourceSetting, image.getObjectKey());
                if (content != null && content.length > 0) {
                    return content;
                }
            } catch (Exception e) {
                // 按对象键下载不可用时回退直链（例如私有桶没配 AK/SK 的副本场景）
                if (isRetryable(e)) {
                    throw e instanceof RuntimeException ? (RuntimeException) e : new BizException(e.getMessage());
                }
                log.debug("按对象键下载失败（回退直链）: {}", e.getMessage());
            }
        }
        if (!StringUtils.hasText(image.getUrl())) {
            throw new BizException("该图片没有可用的访问地址，无法读取原始内容");
        }
        HttpSupport.BinaryResult result = HttpSupport.getBinary(image.getUrl(), Map.of());
        if (result.status() < 200 || result.status() >= 300) {
            throw new BizException("下载源文件失败 (HTTP " + result.status() + ")：" + image.getUrl());
        }
        if (result.body() == null || result.body().length == 0) {
            throw new BizException("下载到的文件为空：" + image.getUrl());
        }
        return result.body();
    }

    private void deleteSourceQuietly(MigrationTask task, ImageEntity image, StorageSetting sourceSetting) {
        try {
            StorageService sourceService = storageServiceFactory.get(sourceSetting.type());
            if (sourceService.supportsDelete()) {
                withRetry("删除源文件 " + image.getObjectKey(),
                        () -> {
                            sourceService.delete(sourceSetting, image.getObjectKey());
                            return Boolean.TRUE;
                        });
            }
        } catch (Exception e) {
            // 迁移已经成功，源文件删不掉只记警告，不影响结果
            task.recordError(image.getOriginalName(), "源文件删除失败（数据已迁移）: " + e.getMessage());
        }
    }

    private List<ImageEntity> resolveImages(Long userId, MigrateRequest request) {
        LambdaQueryWrapper<ImageEntity> wrapper = Wrappers.<ImageEntity>lambdaQuery()
                .eq(ImageEntity::getUserId, userId);
        if (request.getImageIds() != null && !request.getImageIds().isEmpty()) {
            wrapper.in(ImageEntity::getId, request.getImageIds());
        } else {
            if (!request.isMigrateAll()) {
                throw new BizException("请先勾选图片，或确认「迁移当前筛选的全部图片」");
            }
            if (request.getSourceStorageConfigId() != null) {
                wrapper.eq(ImageEntity::getStorageConfigId, request.getSourceStorageConfigId());
            }
            if (StringUtils.hasText(request.getKeyword())) {
                String keyword = request.getKeyword().trim();
                wrapper.and(w -> w.like(ImageEntity::getOriginalName, keyword)
                        .or().like(ImageEntity::getObjectKey, keyword));
            }
        }
        wrapper.orderByAsc(ImageEntity::getId);
        return imageMapper.selectList(wrapper);
    }

    private MigrationTaskVO toVO(MigrationTask task) {
        MigrationTaskVO vo = new MigrationTaskVO();
        vo.setTaskId(task.id);
        vo.setStatus(task.status);
        vo.setSourceName(task.sourceName);
        vo.setTargetName(task.targetName);
        vo.setConcurrency(task.concurrency);
        vo.setTotal(task.total);
        vo.setDone(task.done.get());
        vo.setSucceeded(task.succeeded.get());
        vo.setFailed(task.failed.get());
        vo.setSkipped(task.skipped.get());
        vo.setPercent(task.total <= 0 ? 0 : (int) (task.done.get() * 100L / task.total));
        vo.setCurrentFile(task.currentFile);
        vo.setMessage(task.message);
        vo.setStartedAt(task.startedAt);
        vo.setFinishedAt(task.finishedAt);
        vo.setErrors(task.errorGroups.entrySet().stream()
                .map(entry -> {
                    MigrationErrorVO item = new MigrationErrorVO();
                    item.setReason(entry.getKey());
                    item.setCount(entry.getValue().count.get());
                    synchronized (entry.getValue().samples) {
                        item.setSamples(new ArrayList<>(entry.getValue().samples));
                    }
                    return item;
                })
                .sorted(Comparator.comparingInt(MigrationErrorVO::getCount).reversed())
                .collect(Collectors.toList()));
        return vo;
    }

    /**
     * 迁移过程中的对象键分配。
     * 「保留原目录结构」与「保留原文件名」是两个独立开关；多线程下需要串行分配，故方法加锁。
     * 分片号统一由 ShardAllocator 分配，与正常上传共用同一套「填满最后一个分片再顺延」的规则。
     */
    private final class KeyBuilder {

        private final Long userId;
        private final Long targetConfigId;
        private final StorageSetting targetSetting;
        private final String presetPrefix;
        private final boolean preserveDirectory;
        private final boolean preserveFileName;

        KeyBuilder(Long userId, Long targetConfigId, StorageSetting targetSetting,
                   String presetPrefix, boolean preserveDirectory, boolean preserveFileName) {
            this.userId = userId;
            this.targetConfigId = targetConfigId;
            this.targetSetting = targetSetting;
            this.presetPrefix = normalizePrefix(presetPrefix);
            this.preserveDirectory = preserveDirectory;
            this.preserveFileName = preserveFileName;
        }

        synchronized String next(ImageEntity image, StorageSetting sourceSetting, MigrationTask task) {
            String prefix = effectivePrefix();
            String relativeDir = preserveDirectory
                    ? ObjectKeys.relativeDirectory(image.getObjectKey(), sourceSetting.pathPrefix())
                    : dateDirectory(prefix);
            String fileName = preserveFileName
                    ? ObjectKeys.fileName(image.getObjectKey())
                    : ObjectKeys.randomFileName(image.getOriginalName());

            if (preserveFileName) {
                return uniqueKey(prefix, relativeDir, fileName, task);
            }
            String key = prefix + relativeDir + fileName;
            task.usedKeys.add(key);
            return key;
        }

        /** 目标目录：{yyyy}/{MM}/{dd}/[{分片}/]，相对于目标前缀 */
        private String dateDirectory(String prefix) {
            String relative = ObjectKeys.dayDirectory(prefix).substring(prefix.length());
            int shard = shardAllocator.nextShard(userId, targetConfigId, prefix);
            return shard > 0 ? relative + String.format("%03d/", shard) : relative;
        }

        /** 重名时追加 -1、-2 …，同时避开任务内已用和库里已有的键 */
        private String uniqueKey(String prefix, String relativeDir, String fileName, MigrationTask task) {
            String base = fileName;
            String extension = "";
            int dot = fileName.lastIndexOf('.');
            if (dot > 0) {
                base = fileName.substring(0, dot);
                extension = fileName.substring(dot);
            }
            String candidate = prefix + relativeDir + fileName;
            int index = 1;
            while (task.usedKeys.contains(candidate) || objectKeyExists(candidate)) {
                candidate = prefix + relativeDir + base + "-" + index + extension;
                index++;
                if (index > 10000) {
                    break;
                }
            }
            task.usedKeys.add(candidate);
            return candidate;
        }

        private boolean objectKeyExists(String objectKey) {
            Long count = imageMapper.selectCount(Wrappers.<ImageEntity>lambdaQuery()
                    .eq(ImageEntity::getUserId, userId)
                    .eq(ImageEntity::getStorageConfigId, targetConfigId)
                    .eq(ImageEntity::getObjectKey, objectKey));
            return count != null && count > 0;
        }

        private String effectivePrefix() {
            return presetPrefix != null ? presetPrefix : targetSetting.pathPrefix();
        }

        private String normalizePrefix(String prefix) {
            if (!StringUtils.hasText(prefix)) {
                return null;
            }
            String value = prefix.trim().replaceAll("^/+", "");
            return value.isEmpty() ? null : (value.endsWith("/") ? value : value + "/");
        }
    }

    /** 任务运行时状态 */
    private static final class MigrationTask {

        private final String id;
        private final Long userId;
        private final String sourceName;
        private final String targetName;
        private final int concurrency;
        private final int total;
        private final AtomicInteger done = new AtomicInteger();
        private final AtomicInteger succeeded = new AtomicInteger();
        private final AtomicInteger failed = new AtomicInteger();
        private final AtomicInteger skipped = new AtomicInteger();
        /** 失败原因 -> 归并结果（多线程安全） */
        private final Map<String, ErrorGroup> errorGroups = new ConcurrentHashMap<>();
        /** 本次任务已经用掉的目标对象键，用于保留文件名时的去重（多线程安全） */
        private final Set<String> usedKeys = ConcurrentHashMap.newKeySet();
        private final long startedAt = System.currentTimeMillis();

        private volatile String status = "PENDING";
        private volatile String currentFile;
        private volatile String message;
        private volatile Long finishedAt;
        private volatile boolean cancelled;

        MigrationTask(String id, Long userId, int total, String sourceName, String targetName, int concurrency) {
            this.id = id;
            this.userId = userId;
            this.total = total;
            this.sourceName = sourceName;
            this.targetName = targetName;
            this.concurrency = concurrency;
        }

        /** 记录一次失败：同一原因的合并成一条，计数完整保留 */
        void recordError(String fileName, String reason) {
            String key = normalizeReason(reason);
            ErrorGroup group = errorGroups.computeIfAbsent(key, k -> new ErrorGroup());
            group.count.incrementAndGet();
            if (group.samples.size() < MAX_SAMPLES_PER_REASON) {
                group.samples.add(StringUtils.hasText(fileName) ? fileName : "(未知文件名)");
            }
        }
    }

    /** 同一种失败原因归并后的计数与示例 */
    private static final class ErrorGroup {

        private final AtomicInteger count = new AtomicInteger();
        private final List<String> samples = java.util.Collections.synchronizedList(new ArrayList<>());
    }

    /**
     * 把失败原因归一化，避免同一种错误因为 URL / 文件名不同被拆成一堆。
     * 例如 "下载源文件失败 (HTTP 404)：https://x/a.png" 会变成 "下载源文件失败 (HTTP 404)：<地址>"。
     */
    private static String normalizeReason(String reason) {
        if (!StringUtils.hasText(reason)) {
            return "未知错误";
        }
        String normalized = reason.replaceAll("https?://\\S+", "<地址>").trim();
        return normalized.isEmpty() ? "未知错误" : normalized;
    }
}
