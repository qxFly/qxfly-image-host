package com.qxfly.imagehost.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.qxfly.imagehost.common.BizException;
import com.qxfly.imagehost.dto.RepoDedupeRequest;
import com.qxfly.imagehost.dto.RepoDedupeResultVO;
import com.qxfly.imagehost.entity.ImageEntity;
import com.qxfly.imagehost.entity.StorageConfigEntity;
import com.qxfly.imagehost.mapper.ImageMapper;
import com.qxfly.imagehost.storage.StorageServiceFactory;
import com.qxfly.imagehost.storage.StorageSetting;
import com.qxfly.imagehost.storage.support.ImageTypes;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 仓库重复文件清理。
 * <p>
 * 关键点：<b>同名不等于同内容</b>。系统在遇到重名时会生成 @@foo-1.png@@ 这样的名字，
 * 那个文件可能是完全不同的另一张图。所以判断重复不能只看文件名，必须比对 blob SHA：
 * <ul>
 *   <li>内容相同 -> 真重复，删掉多余的很安全</li>
 *   <li>内容不同 -> 同名但不同图，默认<b>不删</b>，只在结果里列出来供人工判断</li>
 * </ul>
 * 只有 @@-N@@（1~2 位数字）后缀会被当成重复候选；@@(1)@@、@@(2)@@ 这类名字完全不动。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RepoDedupeService {

    /** 只认 1~2 位数字的 -N 后缀，避免把 foo-2024.png 这种正常名字当成重复 */
    private static final Pattern NUMERIC_SUFFIX = Pattern.compile("-(\\d{1,2})$");
    private static final int MAX_SAMPLES = 15;

    private final StorageConfigService storageConfigService;
    private final StorageServiceFactory storageServiceFactory;
    private final GithubTreeMover treeMover;
    private final ImageMapper imageMapper;

    public RepoDedupeResultVO preview(Long userId, RepoDedupeRequest request) {
        request.setDryRun(true);
        return run(userId, request);
    }

    public RepoDedupeResultVO execute(Long userId, RepoDedupeRequest request) {
        request.setDryRun(false);
        return run(userId, request);
    }

    private RepoDedupeResultVO run(Long userId, RepoDedupeRequest request) {
        StorageConfigEntity config = storageConfigService.requireOwned(userId, request.getStorageConfigId());
        if (!"GITHUB".equals(config.getType())) {
            throw new BizException("这个工具只支持 GitHub 存储");
        }
        StorageSetting setting = storageConfigService.toSetting(config);
        String directory = normalizeDir(request.getDirectory());

        GithubTreeMover.Snapshot snapshot = treeMover.snapshot(setting);
        Map<String, String> shas = snapshot.blobShas();

        List<String> paths = new ArrayList<>();
        for (String path : snapshot.paths()) {
            if (path.startsWith(directory) && ImageTypes.isImage(path)) {
                paths.add(path);
            }
        }
        paths.sort(Comparator.naturalOrder());

        // 库里已有的对象键：用于决定保留哪一份，以及删完怎么改库
        Set<String> known = new HashSet<>();
        for (ImageEntity row : imageMapper.selectList(Wrappers.<ImageEntity>lambdaQuery()
                .select(ImageEntity::getObjectKey)
                .eq(ImageEntity::getUserId, userId)
                .eq(ImageEntity::getStorageConfigId, config.getId()))) {
            known.add(row.getObjectKey());
        }

        // 按“去掉 -N 后缀的文件名”分组：同名但不同目录的会落到同一组
        Map<String, List<String>> groups = new LinkedHashMap<>();
        for (String path : paths) {
            groups.computeIfAbsent(baseName(fileNameOf(path)), k -> new ArrayList<>()).add(path);
        }

        RepoDedupeResultVO result = new RepoDedupeResultVO();
        result.setDryRun(request.isDryRun());
        result.setScanned(paths.size());

        List<String> toDelete = new ArrayList<>();
        Map<String, String> keeperOf = new HashMap<>();
        List<String> diffSamples = new ArrayList<>();
        List<String> orphanSamples = new ArrayList<>();
        int duplicateGroups = 0;
        int sameShaGroups = 0;
        int diffShaGroups = 0;
        int suffixedWithBase = 0;
        int orphanSuffixed = 0;

        for (Map.Entry<String, List<String>> entry : groups.entrySet()) {
            List<String> members = entry.getValue();
            if (members.size() < 2) {
                continue;
            }
            duplicateGroups++;
            List<String> suffixed = new ArrayList<>();
            List<String> plain = new ArrayList<>();
            for (String path : members) {
                if (hasNumericSuffix(fileNameOf(path))) {
                    suffixed.add(path);
                } else {
                    plain.add(path);
                }
            }
            if (plain.isEmpty()) {
                // 只有 -N 文件、没有同名基名：可能是唯一的图，不删，单独报出来
                orphanSuffixed += suffixed.size();
                for (String path : suffixed) {
                    if (orphanSamples.size() < MAX_SAMPLES) {
                        orphanSamples.add(path);
                    }
                }
                continue;
            }

            // 同名（无后缀）的多副本：优先保留库里有记录的那一份
            String keeper = plain.stream().filter(known::contains).findFirst().orElse(plain.get(0));
            keeperOf.put(entry.getKey(), keeper);
            String keeperSha = shas.get(keeper);

            boolean groupSameSha = true;
            for (String path : plain) {
                if (path.equals(keeper)) {
                    continue;
                }
                if (keeperSha != null && keeperSha.equals(shas.get(path))) {
                    toDelete.add(path);
                } else {
                    groupSameSha = false;
                    if (diffSamples.size() < MAX_SAMPLES) {
                        diffSamples.add("同名不同内容，已跳过：" + path + "（保留 " + keeper + "）");
                    }
                }
            }

            for (String path : suffixed) {
                suffixedWithBase++;
                boolean sameAsKeeper = keeperSha != null && keeperSha.equals(shas.get(path));
                if (sameAsKeeper || request.isForceDeleteSuffixed()) {
                    toDelete.add(path);
                } else {
                    groupSameSha = false;
                    if (diffSamples.size() < MAX_SAMPLES) {
                        diffSamples.add("带 -N 后缀但内容不同，已跳过：" + path);
                    }
                }
            }
            if (groupSameSha) {
                sameShaGroups++;
            } else {
                diffShaGroups++;
            }
        }

        result.setDuplicateGroups(duplicateGroups);
        result.setSameShaGroups(sameShaGroups);
        result.setDiffShaGroups(diffShaGroups);
        result.setSuffixedWithBase(suffixedWithBase);
        result.setOrphanSuffixed(orphanSuffixed);
        result.setToDelete(toDelete.size());
        result.setDiffSamples(diffSamples);
        result.setOrphanSamples(orphanSamples);
        for (String path : toDelete) {
            if (result.getSamples().size() < MAX_SAMPLES) {
                result.getSamples().add(path);
            }
        }

        if (request.isDryRun()) {
            result.setMessage("分析完成：扫描 " + paths.size() + " 个图片，" + duplicateGroups + " 组重名，"
                    + "其中内容完全相同的 " + sameShaGroups + " 组；建议删除 " + toDelete.size() + " 个"
                    + (diffShaGroups > 0 ? "；有 " + diffShaGroups + " 组同名但内容不同，已跳过" : "")
                    + (orphanSuffixed > 0 ? "；另有 " + orphanSuffixed + " 个带 -N 后缀但找不到同名基名，未删" : ""));
            return result;
        }

        if (toDelete.isEmpty()) {
            result.setMessage("没有需要删除的重复文件");
            return result;
        }

        int deleted = treeMover.delete(setting, toDelete, null);
        result.setDeleted(deleted);
        result.setCommits((deleted + 199) / 200);

        // 同步数据库：删掉的记录，若保留的那份没有记录就改写过去，否则直接删掉
        int removed = 0;
        int rewritten = 0;
        Map<String, ImageEntity> rowsByKey = new HashMap<>();
        for (ImageEntity row : imageMapper.selectList(Wrappers.<ImageEntity>lambdaQuery()
                .eq(ImageEntity::getUserId, userId)
                .eq(ImageEntity::getStorageConfigId, config.getId()))) {
            rowsByKey.putIfAbsent(row.getObjectKey(), row);
        }
        Set<String> keeperHasRow = new HashSet<>();
        for (String keeper : keeperOf.values()) {
            if (rowsByKey.containsKey(keeper)) {
                keeperHasRow.add(keeper);
            }
        }
        var service = storageServiceFactory.get(setting.type());
        for (String path : toDelete) {
            ImageEntity row = rowsByKey.get(path);
            if (row == null) {
                continue;
            }
            String group = baseName(fileNameOf(path));
            String keeper = keeperOf.get(group);
            if (keeper != null && !keeperHasRow.contains(keeper)) {
                ImageEntity update = new ImageEntity();
                update.setId(row.getId());
                update.setObjectKey(keeper);
                update.setUrl(service.publicUrl(setting, keeper));
                update.setThumbUrl(null);
                imageMapper.updateById(update);
                keeperHasRow.add(keeper);
                rewritten++;
            } else {
                imageMapper.deleteById(row.getId());
                removed++;
            }
        }
        result.setDbRemoved(removed);
        result.setDbRewritten(rewritten);
        result.setMessage("已删除 " + deleted + " 个重复文件；数据库清理 " + removed + " 条、改写 " + rewritten + " 条"
                + (diffShaGroups > 0 ? "；有 " + diffShaGroups + " 组同名但内容不同，未动" : ""));
        log.info("重复清理完成：删除 {} 个，库清理 {} 条，改写 {} 条", deleted, removed, rewritten);
        return result;
    }

    private String fileNameOf(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }

    private boolean hasNumericSuffix(String name) {
        String base = stripExtension(name);
        return NUMERIC_SUFFIX.matcher(base).find();
    }

    /**
     * 去掉 -N 后缀：foo-1.png -> foo.png；(1).png 这种不动。
     * <p>
     * 会<b>反复剥离</b>，因为后缀可能叠加：foo-1-1.png -> foo.png。
     * 只剥一层的话，foo-1-1.png 会被分到 foo-1 那一组，而那组里没有“无后缀”的基名，
     * 结果被当成孤本跳过去，重复文件就漏掉了。
     */
    private String baseName(String name) {
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String extension = dot > 0 ? name.substring(dot) : "";
        Matcher matcher = NUMERIC_SUFFIX.matcher(base);
        boolean stripped = false;
        while (matcher.find() && matcher.start() > 0) {
            base = base.substring(0, matcher.start());
            stripped = true;
            matcher = NUMERIC_SUFFIX.matcher(base);
        }
        return stripped ? base + extension : name;
    }

    private String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private String normalizeDir(String dir) {
        if (!StringUtils.hasText(dir)) {
            return "";
        }
        String value = dir.trim().replaceAll("^/+", "");
        return value.isEmpty() ? "" : (value.endsWith("/") ? value : value + "/");
    }
}
