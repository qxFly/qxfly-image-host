package com.qxfly.imagehost.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.qxfly.imagehost.common.BizException;
import com.qxfly.imagehost.config.AppProperties;
import com.qxfly.imagehost.dto.SettingsRequest;
import com.qxfly.imagehost.entity.AppSettingEntity;
import com.qxfly.imagehost.mapper.AppSettingMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 系统设置（key-value 落库，改完立即生效，不用重启）。
 * <p>
 * 目前只有一个键：单张图片大小上限。取值语义：
 * <ul>
 *   <li>{@code > 0}：上限字节数</li>
 *   <li>{@code <= 0}（即 {@link #UNLIMITED}）：<b>无上限</b></li>
 * </ul>
 * 数据库里没有这一行时，用 application.yml 里的 {@code app.upload.max-size} 作为出厂默认值。
 * <p>
 * 注意：Servlet 层的 multipart 上限默认也放开了（application.yml 的
 * {@code spring.servlet.multipart.max-file-size: -1}），真正的限制由这里在应用层判断，
 * 所以「无上限」是真的不限制；需要服务端硬兜底时用环境变量 {@code UPLOAD_SERVLET_MAX}。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AppSettingService {

    /** 单张图片大小上限的设置键 */
    public static final String KEY_MAX_UPLOAD_SIZE = "upload.max-size";
    /** 登录失败几次后锁定 */
    public static final String KEY_LOGIN_MAX_ATTEMPTS = "login.max-attempts";
    /** 锁定时长（分钟） */
    public static final String KEY_LOGIN_LOCK_MINUTES = "login.lock-minutes";
    /** 防盗链开关 */
    public static final String KEY_HOTLINK_ENABLED = "hotlink.enabled";
    /** 防盗链白名单域名（逗号分隔，子域自动放行） */
    public static final String KEY_HOTLINK_DOMAINS = "hotlink.domains";
    /** 没有 Referer 时是否放行（浏览器隐私设置、直接粘贴地址都会没有 Referer） */
    public static final String KEY_HOTLINK_ALLOW_EMPTY = "hotlink.allow-empty";

    private static final int DEFAULT_LOGIN_MAX_ATTEMPTS = 5;
    private static final int DEFAULT_LOGIN_LOCK_MINUTES = 15;

    /** 无上限 */
    public static final long UNLIMITED = -1L;

    /** 设置值本身的上限（1 TB），只用来拦明显的笔误 */
    private static final long MAX_ACCEPTABLE = 1024L * 1024 * 1024 * 1024;

    private final AppSettingMapper appSettingMapper;
    private final AppProperties appProperties;
    private final AuditService auditService;

    /** 单张图片大小上限（字节）；{@code <= 0} 表示无上限 */
    public long maxUploadSize() {
        String value = read(KEY_MAX_UPLOAD_SIZE);
        if (!StringUtils.hasText(value)) {
            return appProperties.getUpload().getMaxSize();
        }
        try {
            long parsed = Long.parseLong(value.trim());
            return parsed <= 0 ? UNLIMITED : parsed;
        } catch (NumberFormatException e) {
            log.warn("系统设置 {} 的值不合法（{}），回退到默认值", KEY_MAX_UPLOAD_SIZE, value);
            return appProperties.getUpload().getMaxSize();
        }
    }

    public boolean unlimited() {
        return maxUploadSize() <= 0;
    }

    /** 超限时给用户看的文案，例如「30.0 MB」或「无上限」 */
    public String describeLimit() {
        long limit = maxUploadSize();
        return limit <= 0 ? "无上限" : readable(limit);
    }

    /** 是否超过上限（无上限时永远不超） */
    public boolean exceeds(long bytes) {
        long limit = maxUploadSize();
        return limit > 0 && bytes > limit;
    }

    /** 防盗链是否开启（默认关闭，避免影响已有引用） */
    public boolean hotlinkEnabled() {
        return "true".equalsIgnoreCase(readOrDefault(KEY_HOTLINK_ENABLED, "false"));
    }

    /** 防盗链白名单；子域自动放行 */
    public List<String> hotlinkDomains() {
        String value = readOrDefault(KEY_HOTLINK_DOMAINS, "");
        if (!StringUtils.hasText(value)) {
            return List.of();
        }
        List<String> domains = new ArrayList<>();
        for (String item : value.split("[,\\s]+")) {
            String domain = item.trim().toLowerCase().replaceAll("^https?://", "").replaceAll("/.*$", "");
            if (!domain.isEmpty() && !domains.contains(domain)) {
                domains.add(domain);
            }
        }
        return domains;
    }

    /** 没有 Referer 时是否放行（默认放行：直接打开图片地址、隐私设置都会没有 Referer） */
    public boolean hotlinkAllowEmpty() {
        return !"false".equalsIgnoreCase(readOrDefault(KEY_HOTLINK_ALLOW_EMPTY, "true"));
    }

    private String readOrDefault(String key, String fallback) {
        String value = read(key);
        return StringUtils.hasText(value) ? value.trim() : fallback;
    }

    /** 登录失败几次后锁定（1~100） */
    public int loginMaxAttempts() {
        return (int) readLong(KEY_LOGIN_MAX_ATTEMPTS, DEFAULT_LOGIN_MAX_ATTEMPTS, 1, 100);
    }

    /** 锁定时长（分钟，1~1440） */
    public int loginLockMinutes() {
        return (int) readLong(KEY_LOGIN_LOCK_MINUTES, DEFAULT_LOGIN_LOCK_MINUTES, 1, 1440);
    }

    private long readLong(String key, long fallback, long min, long max) {
        String value = read(key);
        if (!StringUtils.hasText(value)) {
            return fallback;
        }
        try {
            long parsed = Long.parseLong(value.trim());
            return Math.min(Math.max(parsed, min), max);
        } catch (NumberFormatException e) {
            log.warn("系统设置 {} 的值不合法（{}），回退到默认值 {}", key, value, fallback);
            return fallback;
        }
    }

    /** 一次提交多项设置；没提交的项保持不变 */
    @Transactional
    public void update(SettingsRequest request) {
        StringBuilder changed = new StringBuilder();
        if (request.getUnlimitedUploadSize() != null || request.getMaxUploadSize() != null) {
            changed.append(request.getUnlimitedUploadSize() != null ? "单张上限=无上限" : "单张上限")
                    .append(request.getUnlimitedUploadSize() != null ? "" :
                            "=" + readable(request.getMaxUploadSize() == null ? 0 : request.getMaxUploadSize()))
                    .append("；");
        }
        if (request.getLoginMaxAttempts() != null) {
            changed.append("登录锁定次数=").append(request.getLoginMaxAttempts()).append("；");
        }
        if (request.getLoginLockMinutes() != null) {
            changed.append("锁定时长=").append(request.getLoginLockMinutes()).append(" 分钟；");
        }
        updateInternal(request);
        if (changed.length() > 0) {
            auditService.log(AuditService.Actions.SETTINGS_UPDATE, "SETTINGS", null, "修改系统设置：" + changed);
        }
    }

    @Transactional
    public void updateInternal(SettingsRequest request) {
        if (request.getUnlimitedUploadSize() != null || request.getMaxUploadSize() != null) {
            updateMaxUploadSize(request.getMaxUploadSize(), request.getUnlimitedUploadSize());
        }
        if (request.getLoginMaxAttempts() != null) {
            int value = request.getLoginMaxAttempts();
            if (value < 1 || value > 100) {
                throw new BizException("登录失败锁定次数要在 1 ~ 100 之间");
            }
            write(KEY_LOGIN_MAX_ATTEMPTS, String.valueOf(value));
            log.info("登录失败锁定次数已改为：{}", value);
        }
        if (request.getLoginLockMinutes() != null) {
            int value = request.getLoginLockMinutes();
            if (value < 1 || value > 1440) {
                throw new BizException("锁定时长要在 1 ~ 1440 分钟之间");
            }
            write(KEY_LOGIN_LOCK_MINUTES, String.valueOf(value));
            log.info("登录锁定时长已改为：{} 分钟", value);
        }
        if (request.getHotlinkEnabled() != null) {
            write(KEY_HOTLINK_ENABLED, String.valueOf(request.getHotlinkEnabled()));
            log.info("防盗链已{}", request.getHotlinkEnabled() ? "开启" : "关闭");
        }
        if (request.getHotlinkDomains() != null) {
            write(KEY_HOTLINK_DOMAINS, request.getHotlinkDomains().trim());
        }
        if (request.getHotlinkAllowEmpty() != null) {
            write(KEY_HOTLINK_ALLOW_EMPTY, String.valueOf(request.getHotlinkAllowEmpty()));
        }
    }

    @Transactional
    public void updateMaxUploadSize(Long maxUploadSize, Boolean unlimitedUploadSize) {
        if (Boolean.TRUE.equals(unlimitedUploadSize)) {
            write(KEY_MAX_UPLOAD_SIZE, String.valueOf(UNLIMITED));
            log.info("单张文件大小上限已改为：无上限");
            return;
        }
        if (maxUploadSize == null) {
            throw new BizException("请填写单张文件大小上限，或打开「无上限」");
        }
        if (maxUploadSize <= 0) {
            throw new BizException("上限必须大于 0；要取消限制请打开「无上限」");
        }
        if (maxUploadSize > MAX_ACCEPTABLE) {
            throw new BizException("上限过大（最大 " + readable(MAX_ACCEPTABLE) + "）");
        }
        write(KEY_MAX_UPLOAD_SIZE, String.valueOf(maxUploadSize));
        log.info("单张文件大小上限已改为：{}", readable(maxUploadSize));
    }

    /** 给前端用的设置快照 */
    public Map<String, Object> snapshot() {
        long limit = maxUploadSize();
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("maxUploadSize", limit);
        map.put("maxUploadSizeText", describeLimit());
        map.put("unlimitedUploadSize", limit <= 0);
        map.put("defaultMaxUploadSize", appProperties.getUpload().getMaxSize());
        map.put("maxUploadSizeLimit", MAX_ACCEPTABLE);
        map.put("loginMaxAttempts", loginMaxAttempts());
        map.put("loginLockMinutes", loginLockMinutes());
        map.put("hotlinkEnabled", hotlinkEnabled());
        map.put("hotlinkDomains", String.join(",", hotlinkDomains()));
        map.put("hotlinkAllowEmpty", hotlinkAllowEmpty());
        return map;
    }

    private String read(String key) {
        AppSettingEntity row = appSettingMapper.selectOne(Wrappers.<AppSettingEntity>lambdaQuery()
                .eq(AppSettingEntity::getSettingKey, key)
                .last("LIMIT 1"));
        return row == null ? null : row.getSettingValue();
    }

    private void write(String key, String value) {
        AppSettingEntity row = appSettingMapper.selectOne(Wrappers.<AppSettingEntity>lambdaQuery()
                .eq(AppSettingEntity::getSettingKey, key)
                .last("LIMIT 1"));
        if (row == null) {
            AppSettingEntity created = new AppSettingEntity();
            created.setSettingKey(key);
            created.setSettingValue(value);
            appSettingMapper.insert(created);
            return;
        }
        // updateById 会跳过 null 字段，这里显式 set
        appSettingMapper.update(null, Wrappers.<AppSettingEntity>lambdaUpdate()
                .eq(AppSettingEntity::getId, row.getId())
                .set(AppSettingEntity::getSettingValue, value));
    }

    /** 字节数转可读文本 */
    public static String readable(long bytes) {
        if (bytes <= 0) {
            return "无上限";
        }
        String[] units = {"B", "KB", "MB", "GB", "TB"};
        double value = bytes;
        int unit = 0;
        while (value >= 1024 && unit < units.length - 1) {
            value /= 1024;
            unit++;
        }
        return (unit == 0 ? String.valueOf((long) value) : String.format("%.1f", value)) + " " + units[unit];
    }
}
