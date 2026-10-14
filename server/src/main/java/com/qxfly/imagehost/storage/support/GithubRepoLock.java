package com.qxfly.imagehost.storage.support;

import com.qxfly.imagehost.storage.StorageSetting;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * GitHub 同一仓库分支的写入锁。
 * <p>
 * Contents API 每写一个文件就要向分支提交一次（Git tree 搬移同理），
 * 两个提交同时打同一个分支，落后的那个必然收到
 * 409 <code>is at xxx but expected yyy</code>。
 * <p>
 * 靠上层重试虽然也能过，但重试之间还会互相再撞（退避时间相近，形成惊群），
 * 所以这里直接把「同一个仓库分支的写操作」串行化：不同仓库互不影响，同一仓库才排队。
 */
@Component
public class GithubRepoLock {

    private final Map<String, Object> locks = new ConcurrentHashMap<>();

    public <T> T withLock(StorageSetting setting, Supplier<T> action) {
        synchronized (lockFor(setting)) {
            return action.get();
        }
    }

    public void withLock(StorageSetting setting, Runnable action) {
        withLock(setting, () -> {
            action.run();
            return null;
        });
    }

    /** 锁的粒度：owner/repo@branch */
    private Object lockFor(StorageSetting setting) {
        String key = setting.get("owner") + "/" + setting.get("repo") + "@" + setting.get("branch", "main");
        return locks.computeIfAbsent(key, key0 -> new Object());
    }
}
