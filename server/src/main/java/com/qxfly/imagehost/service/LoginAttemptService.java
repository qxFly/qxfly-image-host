package com.qxfly.imagehost.service;

import com.qxfly.imagehost.common.BizException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 登录失败锁定：同一账号连续失败若干次后锁定一段时间，防止在线暴力破解。
 * <p>
 * 次数与时长在「系统设置」里改（{@code login.max-attempts} / {@code login.lock-minutes}），
 * 默认 5 次 / 15 分钟。计数放在内存里：重启即清空，这是有意的 ——
 * 攻击者拿不到持久的锁，管理员也不用去数据库清锁。
 * <p>
 * 计数窗口是「锁定时长」：最后一次失败后超过窗口没有新的失败，就重新计数，
 * 避免有人每天失败 4 次把正常用户长期锁在门外。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LoginAttemptService {

    private final AppSettingService appSettingService;

    private record Attempt(int count, long lastFailureAt, long lockedUntil) {
    }

    private final Map<String, Attempt> attempts = new ConcurrentHashMap<>();

    /** 锁着就直接抛异常，登录接口在查库之前调用它 */
    public void checkAllowed(String username) {
        String key = key(username);
        Attempt attempt = attempts.get(key);
        if (attempt == null || attempt.lockedUntil() <= System.currentTimeMillis()) {
            return;
        }
        long remainMillis = attempt.lockedUntil() - System.currentTimeMillis();
        long minutes = Math.max(1, (remainMillis + 59_999) / 60_000);
        throw BizException.forbidden("登录失败次数过多，账号已被锁定，请 " + minutes + " 分钟后再试");
    }

    /** 登录失败：累加计数，达到阈值就锁定并返回 true（调用方据此记审计日志） */
    public boolean onFailure(String username) {
        String key = key(username);
        long now = System.currentTimeMillis();
        long windowMillis = lockMinutes() * 60_000L;
        Attempt previous = attempts.get(key);
        int count = 1;
        if (previous != null && now - previous.lastFailureAt() <= windowMillis) {
            count = previous.count() + 1;
        }
        if (count >= maxAttempts()) {
            attempts.put(key, new Attempt(count, now, now + windowMillis));
            log.warn("账号 {} 连续登录失败 {} 次，已锁定 {} 分钟", username, count, lockMinutes());
            return true;
        }
        attempts.put(key, new Attempt(count, now, 0L));
        return false;
    }

    public void onSuccess(String username) {
        attempts.remove(key(username));
    }

    /** 还剩几次机会（用于提示，未锁定时有效） */
    public int remainingAttempts(String username) {
        Attempt attempt = attempts.get(key(username));
        int used = attempt == null ? 0 : attempt.count();
        return Math.max(0, maxAttempts() - used);
    }

    public int maxAttempts() {
        return appSettingService.loginMaxAttempts();
    }

    public int lockMinutes() {
        return appSettingService.loginLockMinutes();
    }

    private String key(String username) {
        return StringUtils.hasText(username) ? username.trim().toLowerCase() : "-";
    }
}
