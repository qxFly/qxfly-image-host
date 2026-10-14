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
    /** 一组最多展示多少张（其余的只计数） */
    private static final int MAX_IMAGES_PER_GROUP = 100;

    private final ImageMapper imageMapper;
    private final ImageService imageService;
    private final UserService userService;

    /** 按内容哈希分组，返回每一组（含组内图片明细） */
    public Map<String, Object> groups(AuthUser user, long page, long size) {
        List<Long> ownerIds = userService.visibleOwnerIds(user);
        List<Map<String, Object>> grouped = imageMapper.selectMaps(new QueryWrapper<ImageEntity>()
                .select("hash", "count(*) as cnt", "sum(size) as total_size", "min(id) as keep_id")
                .in("user_id", ownerIds)
                .eq("deleted", 0)
                .isNotNull("hash")
                .ne("hash", "")
                .groupBy("hash")
                .having("count(*) > 1")
                .orderByAsc("keep_id"));

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
     * @param hash 只清理这一组；为空表示清理全部组
     * @return 移入回收站的张数
     */
    @Transactional
    public int cleanup(AuthUser user, String hash) {
        List<Long> ownerIds = userService.visibleOwnerIds(user);
        QueryWrapper<ImageEntity> wrapper = new QueryWrapper<ImageEntity>()
                .select("hash", "count(*) as cnt", "min(id) as keep_id")
                .in("user_id", ownerIds)
                .eq("deleted", 0)
                .isNotNull("hash")
                .ne("hash", "")
                .groupBy("hash")
                .having("count(*) > 1");
        if (hash != null && !hash.isBlank()) {
            wrapper.eq("hash", hash.trim());
        }
        List<Map<String, Object>> grouped = imageMapper.selectMaps(wrapper);

        int moved = 0;
        for (Map<String, Object> row : grouped) {
            String groupHash = String.valueOf(row.get("hash"));
            long keepId = number(row.get("keep_id"));
            List<ImageEntity> rows = imageMapper.selectList(new QueryWrapper<ImageEntity>()
                    .in("user_id", ownerIds)
                    .eq("deleted", 0)
                    .eq("hash", groupHash)
                    .ne("id", keepId)
                    .orderByAsc("id"));
            rows.sort(Comparator.comparing(ImageEntity::getId));
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

    private long number(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }
}
