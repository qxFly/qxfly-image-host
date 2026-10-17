package com.qxfly.imagehost.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.qxfly.imagehost.config.AppProperties;
import com.qxfly.imagehost.entity.StorageConfigEntity;
import com.qxfly.imagehost.mapper.StorageConfigMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 存储后端定时体检。
 * <p>
 * 把所有「已启用」的存储配置每隔一段时间真实测一次（head bucket / 读目录等，都是只读操作），
 * 失败会写进审计日志 —— 目的是让「凭证过期、桶被删、网络不通」这类问题早点被发现，
 * 而不是等用户上传失败才暴露。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StorageCheckService {

    private final AppProperties appProperties;
    private final StorageConfigMapper storageConfigMapper;
    private final StorageConfigService storageConfigService;
    private final AuditService auditService;

    /** 最近一次体检结果（configId -> 结果），只保留内存副本 */
    private final Map<Long, CheckResult> results = new ConcurrentHashMap<>();

    private ScheduledExecutorService scheduler;

    /** 一次体检的结果 */
    public record CheckResult(Long configId, String name, boolean ok, String message, LocalDateTime checkedAt) {
    }

    @EventListener(ApplicationReadyEvent.class)
    public void startPeriodicCheck() {
        if (!appProperties.getStorageCheck().isEnabled()) {
            log.info("存储后端体检已关闭");
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "storage-check");
            thread.setDaemon(true);
            return thread;
        });
        long hours = Math.max(1, appProperties.getStorageCheck().getIntervalHours());
        scheduler.scheduleWithFixedDelay(this::checkAllQuietly, 1, hours * 60, TimeUnit.MINUTES);
        log.info("存储后端体检已启用：启动 1 分钟后第一次，之后每 {} 小时一次", hours);
    }

    /** 手动触发一次体检，返回所有启用配置的结果（供设置页「立即体检」） */
    public Map<Long, CheckResult> checkNow() {
        return checkAllQuietly();
    }

    /** 最近一次体检结果 */
    public Map<Long, CheckResult> results() {
        return new LinkedHashMap<>(results);
    }

    /** 某个配置的最近结果，未体检过返回 null */
    public CheckResult resultOf(Long configId) {
        return results.get(configId);
    }

    private Map<Long, CheckResult> checkAllQuietly() {
        try {
            return checkAll();
        } catch (Exception e) {
            log.warn("存储后端体检失败: {}", e.getMessage());
            return Map.of();
        }
    }

    private Map<Long, CheckResult> checkAll() {
        Map<Long, CheckResult> fresh = new LinkedHashMap<>();
        int failed = 0;
        for (StorageConfigEntity config : storageConfigMapper.selectList(
                Wrappers.<StorageConfigEntity>lambdaQuery().eq(StorageConfigEntity::getEnabled, 1))) {
            boolean ok;
            String message;
            try {
                storageConfigService.test(config.getUserId(), config.getId());
                ok = true;
                message = "正常";
            } catch (Exception e) {
                ok = false;
                message = e.getMessage();
                failed++;
            }
            CheckResult result = new CheckResult(config.getId(), config.getName(), ok, message, LocalDateTime.now());
            fresh.put(config.getId(), result);
            if (!ok) {
                // 失败才记审计：每天定时跑，成功的不必刷屏
                auditService.log(AuditService.Actions.STORAGE_CHECK, "STORAGE_CONFIG", config.getId(),
                        "体检失败：" + config.getName() + "：" + message);
            }
        }
        results.clear();
        results.putAll(fresh);
        if (failed > 0) {
            log.warn("存储后端体检：{} 个配置异常（共 {} 个）", failed, fresh.size());
        } else {
            log.info("存储后端体检：{} 个配置全部正常", fresh.size());
        }
        return fresh;
    }
}
