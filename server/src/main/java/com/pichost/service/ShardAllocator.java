package com.pichost.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.pichost.config.AppProperties;
import com.pichost.entity.ImageEntity;
import com.pichost.mapper.ImageMapper;
import com.pichost.storage.support.ObjectKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 分片目录分配器。
 * <p>
 * 规则：同一个「存储配置 + 当天目录」下，每个分片目录最多放 shardSize（默认 500）个文件，
 * 填满之后才顺延到下一个分片（001 → 002 → …）。
 * <p>
 * 早先的实现是 <code>分片号 = 当天目录文件总数 / 500 + 1</code>，这个公式只有在每个分片
 * 都被精确填满时才成立。一旦出现偏差（并发上传读到同一个计数、文件被迁移或删除、
 * 导入直接把文件塞进已有分片目录），后面的分片就会远超 500 —— 线上确实出现过 735 个文件的分片。
 * <p>
 * 现在的做法：
 * <ul>
 *   <li>取「当天目录下已有的最大分片号」以及「该分片已有多少文件」，只在最后一个分片上继续填</li>
 *   <li>用进程内的槽位计数 + 加锁分配，并发上传不会拿到同一个位置</li>
 *   <li>槽位在首次使用时从数据库恢复，所以重启后不会从头开始覆盖已有分片</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShardAllocator {

    private final ImageMapper imageMapper;
    private final AppProperties appProperties;

    private final ConcurrentHashMap<String, Slot> slots = new ConcurrentHashMap<>();

    /**
     * 取下一个可用的分片号。
     *
     * @return 分片号；返回 0 表示分片功能已关闭
     */
    public int nextShard(Long userId, Long configId, String pathPrefix) {
        int shardSize = appProperties.getUpload().getShardSize();
        if (shardSize <= 0) {
            return 0;
        }
        String dayDir = ObjectKeys.dayDirectory(pathPrefix);
        String slotKey = userId + "|" + configId + "|" + dayDir;
        Slot slot = slots.computeIfAbsent(slotKey, key -> loadFromDatabase(userId, configId, dayDir, shardSize));
        synchronized (slot) {
            if (slot.used >= shardSize) {
                slot.shard++;
                slot.used = 0;
            }
            slot.used++;
            return slot.shard;
        }
    }

    /**
     * 只看不占：规划接下来 count 个文件会用到哪些分片号。
     * 预览必须用它 —— 否则预览一次就把分片号真的消耗掉了，再执行时分片会莫名跳号。
     */
    public List<Integer> planShards(Long userId, Long configId, String pathPrefix, int count) {
        int shardSize = appProperties.getUpload().getShardSize();
        if (shardSize <= 0) {
            return Collections.nCopies(count, 0);
        }
        String dayDir = ObjectKeys.dayDirectory(pathPrefix);
        String slotKey = userId + "|" + configId + "|" + dayDir;
        Slot slot = slots.computeIfAbsent(slotKey, key -> loadFromDatabase(userId, configId, dayDir, shardSize));
        List<Integer> plan = new ArrayList<>(count);
        synchronized (slot) {
            int shard = slot.shard;
            int used = slot.used;
            for (int i = 0; i < count; i++) {
                if (used >= shardSize) {
                    shard++;
                    used = 0;
                }
                used++;
                plan.add(shard);
            }
        }
        return plan;
    }

    /** 丢弃某个存储配置的槽位缓存（改了路径前缀等场景下用） */
    public void invalidate(Long configId) {
        slots.keySet().removeIf(key -> key.contains("|" + configId + "|"));
    }

    /**
     * 从数据库恢复：找到当天目录下最大的分片号，以及它已经放了多少个文件。
     * 不存在分片目录时从 001 开始。
     */
    private Slot loadFromDatabase(Long userId, Long configId, String dayDir, int shardSize) {
        List<ImageEntity> rows = imageMapper.selectList(Wrappers.<ImageEntity>lambdaQuery()
                .select(ImageEntity::getObjectKey)
                .eq(ImageEntity::getUserId, userId)
                .eq(ImageEntity::getStorageConfigId, configId)
                .likeRight(ImageEntity::getObjectKey, ObjectKeys.escapeLike(dayDir)));

        int maxShard = 0;
        Map<Integer, Integer> perShard = new HashMap<>();
        for (ImageEntity row : rows) {
            String key = row.getObjectKey();
            if (key == null || key.length() <= dayDir.length()) {
                continue;
            }
            String rest = key.substring(dayDir.length());
            int slash = rest.indexOf('/');
            if (slash <= 0) {
                // 直接放在当天目录下的文件（老数据 / 非分片结构），不计入任何分片
                continue;
            }
            Integer shard = parseShard(rest.substring(0, slash));
            if (shard == null) {
                // 形如 2026/10/12/holiday/a.png 的自定义子目录，同样不当成分片
                continue;
            }
            perShard.merge(shard, 1, Integer::sum);
            maxShard = Math.max(maxShard, shard);
        }
        if (perShard.isEmpty()) {
            return new Slot(1, 0);
        }
        int used = perShard.getOrDefault(maxShard, 0);
        if (used >= shardSize) {
            // 最后一个分片已经满了（历史遗留的超限分片也会走到这里），从下一个开始
            log.info("当天目录 {} 的最后一个分片 {:03d} 已有 {} 个文件（上限 {}），顺延到下一个分片",
                    dayDir, maxShard, used, shardSize);
            return new Slot(maxShard, used);
        }
        return new Slot(maxShard, used);
    }

    private Integer parseShard(String segment) {
        if (segment.length() != 3) {
            return null;
        }
        for (int i = 0; i < segment.length(); i++) {
            if (!Character.isDigit(segment.charAt(i))) {
                return null;
            }
        }
        return Integer.parseInt(segment);
    }

    /** 某个「配置 + 当天目录」的分片槽位 */
    private static final class Slot {

        private int shard;
        private int used;

        private Slot(int shard, int used) {
            this.shard = shard;
            this.used = used;
        }
    }
}
