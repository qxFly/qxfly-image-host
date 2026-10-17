package com.qxfly.imagehost.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.qxfly.imagehost.common.PageResult;
import com.qxfly.imagehost.entity.AuditLogEntity;
import com.qxfly.imagehost.mapper.AuditLogMapper;
import com.qxfly.imagehost.security.AuthUser;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 审计日志。
 * <p>
 * 只记「会产生影响的操作」：登录（成功与失败）、上传、删除 / 恢复 / 彻底删除、存储配置增删改、
 * 账号增删改、系统设置修改。读操作不记，免得日志被翻页刷满。
 * <p>
 * 写入是尽力而为：记录失败只打日志，绝不影响主流程。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditService {

    /** 动作码，前端展示时按它翻译成中文 */
    public static final class Actions {
        public static final String LOGIN = "LOGIN";
        public static final String LOGIN_FAILED = "LOGIN_FAILED";
        public static final String LOGIN_LOCKED = "LOGIN_LOCKED";
        public static final String PASSWORD_CHANGE = "PASSWORD_CHANGE";
        public static final String UPLOAD = "UPLOAD";
        public static final String TRASH_MOVE = "TRASH_MOVE";
        public static final String TRASH_RESTORE = "TRASH_RESTORE";
        public static final String IMAGE_PURGE = "IMAGE_PURGE";
        public static final String TRASH_CLEAR = "TRASH_CLEAR";
        public static final String USER_CREATE = "USER_CREATE";
        public static final String USER_UPDATE = "USER_UPDATE";
        public static final String USER_STATUS = "USER_STATUS";
        public static final String USER_PASSWORD = "USER_PASSWORD";
        public static final String USER_DELETE = "USER_DELETE";
        public static final String STORAGE_CREATE = "STORAGE_CREATE";
        public static final String STORAGE_UPDATE = "STORAGE_UPDATE";
        public static final String STORAGE_DELETE = "STORAGE_DELETE";
        public static final String STORAGE_DEFAULT = "STORAGE_DEFAULT";
        public static final String SETTINGS_UPDATE = "SETTINGS_UPDATE";
        public static final String SHARE_CREATE = "SHARE_CREATE";
        public static final String SHARE_DELETE = "SHARE_DELETE";
        /** 存储后端体检（只记失败，避免每天刷屏） */
        public static final String STORAGE_CHECK = "STORAGE_CHECK";

        private Actions() {
        }
    }

    private static final long MAX_PAGE_SIZE = 200;

    private final AuditLogMapper auditLogMapper;
    private final com.qxfly.imagehost.config.AppProperties appProperties;

    /** 每日清理的守护线程；容器关闭时进程退出，不阻塞 JVM */
    private java.util.concurrent.ScheduledExecutorService cleanupExecutor;

    @org.springframework.context.event.EventListener(
            org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void startRetentionTask() {
        int days = appProperties.getAudit().getRetentionDays();
        if (days <= 0) {
            log.info("审计日志保留策略：永久保留（audit.retention-days={}）", days);
            return;
        }
        cleanupExecutor = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "audit-retention");
            thread.setDaemon(true);
            return thread;
        });
        cleanupExecutor.scheduleWithFixedDelay(this::cleanupExpired, 1, 24 * 60, java.util.concurrent.TimeUnit.MINUTES);
        log.info("审计日志保留策略：保留 {} 天，每 24 小时清理一次", days);
    }

    /** 清理超过保留期的日志，返回删除行数 */
    public int cleanupExpired() {
        int days = appProperties.getAudit().getRetentionDays();
        if (days <= 0) {
            return 0;
        }
        java.time.LocalDateTime deadline = java.time.LocalDateTime.now().minusDays(days);
        try {
            int deleted = auditLogMapper.delete(Wrappers.<AuditLogEntity>lambdaQuery()
                    .lt(AuditLogEntity::getCreatedAt, deadline));
            if (deleted > 0) {
                log.info("审计日志清理：删除 {} 条（早于 {}）", deleted, deadline.toLocalDate());
            }
            return deleted;
        } catch (Exception e) {
            log.warn("审计日志清理失败: {}", e.getMessage());
            return 0;
        }
    }

    /** 导出 CSV（只导出筛选命中的前 N 行，避免一次拉爆内存） */
    public byte[] exportCsv(String keyword, String action, Boolean success) {
        int limit = Math.max(1, appProperties.getAudit().getExportMaxRows());
        var wrapper = Wrappers.<AuditLogEntity>lambdaQuery()
                .eq(StringUtils.hasText(action), AuditLogEntity::getAction, action)
                .eq(success != null, AuditLogEntity::getSuccess, Boolean.TRUE.equals(success) ? 1 : 0)
                .orderByDesc(AuditLogEntity::getId)
                .last("LIMIT " + limit);
        if (StringUtils.hasText(keyword)) {
            String value = keyword.trim();
            wrapper.and(w -> w.like(AuditLogEntity::getUsername, value)
                    .or().like(AuditLogEntity::getAction, value)
                    .or().like(AuditLogEntity::getDetail, value)
                    .or().like(AuditLogEntity::getTargetId, value)
                    .or().like(AuditLogEntity::getIp, value));
        }
        StringBuilder csv = new StringBuilder();
        csv.append('\ufeff');   // BOM：Excel 打开中文不乱码
        csv.append("id,时间,操作者,动作,结果,对象类型,对象ID,详情,来源IP,User-Agent\n");
        for (AuditLogEntity row : auditLogMapper.selectList(wrapper)) {
            csv.append(row.getId()).append(',')
                    .append(cell(row.getCreatedAt() == null ? null : row.getCreatedAt().toString())).append(',')
                    .append(cell(row.getUsername())).append(',')
                    .append(cell(row.getAction())).append(',')
                    .append(row.getSuccess() != null && row.getSuccess() == 1 ? "成功" : "失败").append(',')
                    .append(cell(row.getTargetType())).append(',')
                    .append(cell(row.getTargetId())).append(',')
                    .append(cell(row.getDetail())).append(',')
                    .append(cell(row.getIp())).append(',')
                    .append(cell(row.getUserAgent())).append('\n');
        }
        return csv.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    /** CSV 单元格转义 */
    private static String cell(String value) {
        if (value == null) {
            return "";
        }
        String text = value.replace("\r", " ").replace("\n", " ");
        if (text.contains(",") || text.contains("\"")) {
            text = "\"" + text.replace("\"", "\"\"") + "\"";
        }
        return text;
    }

    /** 记录当前登录用户的操作 */
    public void log(String action, String targetType, Object targetId, String detail) {
        AuthUser user = currentUserOrNull();
        write(user == null ? null : user.id(), user == null ? null : user.username(),
                true, action, targetType, targetId, detail);
    }

    /** 记录登录类事件（此时可能还没有登录上下文） */
    public void logLogin(String username, boolean success, Long userId, String action, String detail) {
        write(userId, username, success, action, "USER", userId == null ? username : userId, detail);
    }

    private void write(Long userId, String username, boolean success, String action,
                       String targetType, Object targetId, String detail) {
        try {
            AuditLogEntity entity = new AuditLogEntity();
            entity.setUserId(userId);
            entity.setUsername(trim(username, 64));
            entity.setAction(action);
            entity.setTargetType(targetType);
            entity.setTargetId(targetId == null ? null : trim(String.valueOf(targetId), 128));
            entity.setDetail(trim(detail, 512));
            entity.setSuccess(success ? 1 : 0);
            HttpServletRequest request = currentRequest();
            if (request != null) {
                entity.setIp(clientIp(request));
                entity.setUserAgent(trim(request.getHeader("User-Agent"), 255));
            }
            auditLogMapper.insert(entity);
        } catch (Exception e) {
            // 审计失败不能影响业务
            log.warn("写审计日志失败（{} {}）: {}", action, targetType, e.getMessage());
        }
    }

    /** 分页查询，最新在前 */
    public PageResult<AuditLogEntity> page(long page, long size, String keyword, String action, Boolean success) {
        long current = Math.max(1, page);
        long pageSize = Math.min(Math.max(1, size), MAX_PAGE_SIZE);
        var wrapper = Wrappers.<AuditLogEntity>lambdaQuery()
                .eq(StringUtils.hasText(action), AuditLogEntity::getAction, action)
                .eq(success != null, AuditLogEntity::getSuccess, Boolean.TRUE.equals(success) ? 1 : 0)
                .orderByDesc(AuditLogEntity::getId);
        if (StringUtils.hasText(keyword)) {
            String value = keyword.trim();
            wrapper.and(w -> w.like(AuditLogEntity::getUsername, value)
                    .or().like(AuditLogEntity::getAction, value)
                    .or().like(AuditLogEntity::getDetail, value)
                    .or().like(AuditLogEntity::getTargetId, value)
                    .or().like(AuditLogEntity::getIp, value));
        }
        Page<AuditLogEntity> result = auditLogMapper.selectPage(new Page<>(current, pageSize), wrapper);
        return PageResult.of(result.getTotal(), current, pageSize, result.getRecords());
    }

    private AuthUser currentUserOrNull() {
        try {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication != null && authentication.getPrincipal() instanceof AuthUser user) {
                return user;
            }
        } catch (Exception ignored) {
            // 未登录
        }
        return null;
    }

    private HttpServletRequest currentRequest() {
        try {
            if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
                return attributes.getRequest();
            }
        } catch (Exception ignored) {
            // 后台线程没有请求上下文
        }
        return null;
    }

    /** 优先取反代后的真实来源地址 */
    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (StringUtils.hasText(forwarded)) {
            String first = forwarded.split(",")[0].trim();
            if (StringUtils.hasText(first)) {
                return trim(first, 64);
            }
        }
        String real = request.getHeader("X-Real-IP");
        return trim(StringUtils.hasText(real) ? real : request.getRemoteAddr(), 64);
    }

    private static String trim(String value, int max) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String text = value.trim();
        return text.length() <= max ? text : text.substring(0, max);
    }
}
