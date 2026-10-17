package com.qxfly.imagehost.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.qxfly.imagehost.common.PageResult;
import com.qxfly.imagehost.dto.DuplicateGroupVO;
import com.qxfly.imagehost.entity.ImageEntity;
import com.qxfly.imagehost.mapper.ImageMapper;
import com.qxfly.imagehost.security.AuthUser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 重复图片查找与清理。
 * <p>
 * 「秒传」只能拦住之后的上传，历史数据（多次导入、从别的平台迁移过来、换后端时要保留原件等）
 * 里往往已经存在内容完全相同的多份文件。这里按 sha256 分组把它们找出来，
 * 清理时每组保留一张（默认保留最早上传的那张，它最可能是图库里在用的那条记录），其余移入回收站。
 * <p>
 * 只做哈希相同（内容一致）的分组 —— 不做相似图检测，那需要感知哈希与额外的依赖，
 * 而且误判会真的删掉用户的图。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DuplicateService {

    /** 一次最多返回多少组 */
    private static final int MAX_GROUPS = 200;

    /** 保留策略：最早上传 / 最新上传 / 最大文件（内容都一样，差别在于留哪条记录） */
    public static final String KEEP_EARLIEST = "earliest";
    public static final String KEEP_LATEST = "latest";
    public static final String KEEP_LARGEST = "largest";
    /** 一组最多展示多少张（其余的只计数） */
    private static final int MAX_IMAGES_PER_GROUP = 100;

    private final ImageMapper imageMapper;
    private final ImageService imageService;
    private final UserService userService;

    /** 按内容哈希分组，返回每一组（含组内图片明细） */
    public Map<String, Object> groups(AuthUser user, long page, long size, Long storageConfigId, String directory) {
        List<Long> ownerIds = userService.visibleOwnerIds(user);
        QueryWrapper<ImageEntity> wrapper = new QueryWrapper<ImageEntity>()
                .select("hash", "count(*) as cnt", "sum(size) as total_size", "min(id) as keep_id")
                .in("user_id", ownerIds)
                .eq("deleted", 0)
                .isNotNull("hash")
                .ne("hash", "")
                .groupBy("hash")
                .having("count(*) > 1")
                .orderByAsc("keep_id");
        // 按存储后端 / 目录筛选（与图库同一套语义：目录按前缀匹配，选中父目录含子目录）
        wrapper.eq(storageConfigId != null, "storage_config_id", storageConfigId);
        if (org.springframework.util.StringUtils.hasText(directory)) {
            String prefix = directory.trim().replace("\\", "/");
            if (!prefix.endsWith("/")) {
                prefix = prefix + "/";
            }
            wrapper.likeRight("object_key", prefix);
        }
        List<Map<String, Object>> grouped = imageMapper.selectMaps(wrapper);

        long total = grouped.size();
        int from = (int) Math.min((long) (Math.max(1, page) - 1) * size, grouped.size());
        int to = (int) Math.min(from + size, grouped.size());
        List<Map<String, Object>> current = grouped.subList(from, to);

        List<DuplicateGroupVO> groups = new ArrayList<>(current.size());
        int wasted = 0;
        for (Map<String, Object> row : current) {
            String hash = String.valueOf(row.get("hash"));
            long count = number(row.get("cnt"));
            long totalSize = number(row.get("total_size"));
            long keepId = number(row.get("keep_id"));
            List<DuplicateGroupVO.DuplicateImage> images = new ArrayList<>();
            List<ImageEntity> rows = imageMapper.selectList(new QueryWrapper<ImageEntity>()
                    .in("user_id", ownerIds)
                    .eq("deleted", 0)
                    .eq("hash", hash)
                    .eq(storageConfigId != null, "storage_config_id", storageConfigId)
                    .orderByAsc("id")
                    .last("LIMIT " + MAX_IMAGES_PER_GROUP));
            for (ImageEntity entity : rows) {
                images.add(new DuplicateGroupVO.DuplicateImage(entity.getId(), entity.getOriginalName(),
                        entity.getObjectKey(), entity.getUrl(), entity.getSize(), entity.getCreatedAt(),
                        entity.getStorageConfigId(), entity.getId().equals(keepId)));
            }
            // 能省下的空间 = 总量 - 保留那一张
            long keepSize = images.stream().filter(DuplicateGroupVO.DuplicateImage::keep)
                    .mapToLong(img -> img.size() == null ? 0L : img.size()).findFirst().orElse(0L);
            wasted += Math.max(0L, totalSize - keepSize);
            groups.add(new DuplicateGroupVO(hash, count, totalSize, images));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", total);
        result.put("wastedSize", wasted);
        result.put("page", Math.max(1, page));
        result.put("size", size);
        PageResult<DuplicateGroupVO> pageResult = PageResult.of(total, Math.max(1, page), size, groups);
        result.put("pages", pageResult.getPages());
        result.put("groups", groups);
        return result;
    }

    /**
     * 清理：每组只保留一张，其余移入回收站（不直接物理删除，误判还能捞回来）。
     *
     * @param hash     只清理这一组；为空表示清理全部组
     * @param keep     保留策略：earliest / latest / largest
     * @param storageConfigId 只清理某个存储后端下的（与列表筛选一致）
     * @return 移入回收站的张数
     */
    @Transactional
    public int cleanup(AuthUser user, String hash, String keep, Long storageConfigId) {
        List<Long> ownerIds = userService.visibleOwnerIds(user);
        String strategy = org.springframework.util.StringUtils.hasText(keep) ? keep.trim() : KEEP_EARLIEST;
        QueryWrapper<ImageEntity> wrapper = new QueryWrapper<ImageEntity>()
                .select("hash")
                .in("user_id", ownerIds)
                .eq("deleted", 0)
                .isNotNull("hash")
                .ne("hash", "")
                .eq(storageConfigId != null, "storage_config_id", storageConfigId)
                .groupBy("hash")
                .having("count(*) > 1");
        if (hash != null && !hash.isBlank()) {
            wrapper.eq("hash", hash.trim());
        }
        List<Map<String, Object>> grouped = imageMapper.selectMaps(wrapper);

        int moved = 0;
        for (Map<String, Object> row : grouped) {
            String groupHash = String.valueOf(row.get("hash"));
            List<ImageEntity> members = imageMapper.selectList(new QueryWrapper<ImageEntity>()
                    .in("user_id", ownerIds)
                    .eq("deleted", 0)
                    .eq("hash", groupHash)
                    .eq(storageConfigId != null, "storage_config_id", storageConfigId)
                    .orderByAsc("id"));
            // 保留哪一张取决于策略：内容都一样，差别只是留哪条记录
            ImageEntity keeper = pickKeeper(members, strategy);
            if (keeper == null) {
                continue;
            }
            List<ImageEntity> rows = members.stream()
                    .filter(entity -> !entity.getId().equals(keeper.getId()))
                    .sorted(Comparator.comparing(ImageEntity::getId))
                    .toList();
            for (ImageEntity entity : rows) {
                try {
                    imageService.moveToTrash(entity.getUserId(), entity.getId());
                    moved++;
                } catch (Exception e) {
                    log.warn("清理重复图片失败 id={}: {}", entity.getId(), e.getMessage());
                }
            }
        }
        log.info("重复图片清理：移入回收站 {} 张（每组保留最早的一张）", moved);
        return moved;
    }

    /** 按策略选出保留的那一张 */
    private ImageEntity pickKeeper(List<ImageEntity> members, String strategy) {
        if (members.isEmpty()) {
            return null;
        }
        return switch (strategy) {
            case KEEP_LATEST -> members.get(members.size() - 1);
            case KEEP_LARGEST -> members.stream()
                    .max(Comparator.comparing(entity -> entity.getSize() == null ? 0L : entity.getSize()))
                    .orElse(members.get(0));
            default -> members.get(0);
        };
    }

    private long number(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }
}
