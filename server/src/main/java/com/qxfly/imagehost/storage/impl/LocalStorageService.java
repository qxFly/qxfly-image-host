package com.qxfly.imagehost.storage.impl;

import com.qxfly.imagehost.common.BizException;
import com.qxfly.imagehost.config.AppProperties;
import com.qxfly.imagehost.storage.RemoteObject;
import com.qxfly.imagehost.storage.StorageObject;
import com.qxfly.imagehost.storage.StorageService;
import com.qxfly.imagehost.storage.StorageSetting;
import com.qxfly.imagehost.storage.StorageType;
import com.qxfly.imagehost.storage.UploadResult;
import com.qxfly.imagehost.storage.support.ImageTypes;
import com.qxfly.imagehost.storage.support.ObjectKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 本地存储：图片直接保存在<b>运行本服务的这台机器</b>上。
 * <p>
 * 与其他后端不同，这里没有第三方服务参与：
 * <ul>
 *   <li>写入：{@code <存储目录>/<对象键>}，先写临时文件再改名，避免半截文件</li>
 *   <li>访问：由本服务的 {@code GET /files/{配置ID}/{对象键}} 直接把文件读出来（见 LocalFileController）</li>
 *   <li>搬移：回收站搬运是磁盘内的改名，零流量（{@link #move}）</li>
 *   <li>导入：直接扫描存储目录（{@link #list}）</li>
 * </ul>
 * 对象键按 {@code {路径前缀}/yyyy/MM/dd/[{分片}/]{uuid}.{ext}} 生成，
 * 目录分层与分片逻辑与其他后端完全一致。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LocalStorageService implements StorageService {

    /** 公开访问路径前缀：/files/{配置ID}/{对象键} */
    public static final String URL_PREFIX = "files";

    /** 上传过程中的临时文件前缀，扫描导入时要跳过 */
    private static final String TEMP_PREFIX = ".upload-";

    private final AppProperties appProperties;
    private final com.qxfly.imagehost.service.RequestBase requestBase;

    @Value("${server.port:8080}")
    private int serverPort;

    @Override
    public StorageType type() {
        return StorageType.LOCAL;
    }

    @Override
    public UploadResult upload(StorageSetting setting, StorageObject object) {
        Path target = resolve(setting, object.objectKey());
        try {
            Files.createDirectories(target.getParent());
            // 先写临时文件再原子改名：避免上传中途失败留下半截图片
            Path temp = Files.createTempFile(target.getParent(), TEMP_PREFIX, ".tmp");
            if (object.fileBacked()) {
                // 上传的临时文件 -> 目标目录的临时文件 -> 原子改名，全程流式
                Files.copy(object.contentFile(), temp, StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.write(temp, object.content());
            }
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new BizException("写入本地文件失败: " + target + "（" + e.getMessage() + "）");
        }
        log.info("已保存到本地磁盘: {}（{} 字节）", target, object.size());
        return UploadResult.of(object.objectKey(), publicUrl(setting, object.objectKey()));
    }

    @Override
    public void delete(StorageSetting setting, String objectKey) {
        Path target = resolve(setting, objectKey);
        try {
            Files.deleteIfExists(target);
            // 顺手清掉空的日期目录，别让磁盘上空目录越积越多
            pruneEmptyDirectories(setting, target.getParent());
        } catch (IOException e) {
            throw new BizException("删除本地文件失败: " + target + "（" + e.getMessage() + "）");
        }
    }

    @Override
    public boolean supportsDownload() {
        return true;
    }

    @Override
    public byte[] download(StorageSetting setting, String objectKey) {
        Path target = resolve(setting, objectKey);
        if (!Files.isRegularFile(target)) {
            throw new BizException("本地文件不存在: " + objectKey);
        }
        try {
            return Files.readAllBytes(target);
        } catch (IOException e) {
            throw new BizException("读取本地文件失败: " + target + "（" + e.getMessage() + "）");
        }
    }

    @Override
    public boolean supportsMove() {
        return true;
    }

    @Override
    public void move(StorageSetting setting, String fromKey, String toKey) {
        Path from = resolve(setting, fromKey);
        Path to = resolve(setting, toKey);
        if (!Files.isRegularFile(from)) {
            throw new BizException("待移动的本地文件不存在: " + fromKey);
        }
        try {
            Files.createDirectories(to.getParent());
            if (Files.exists(to)) {
                Files.delete(to);
            }
            try {
                Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(from, to);
            }
            pruneEmptyDirectories(setting, from.getParent());
        } catch (IOException e) {
            throw new BizException("移动本地文件失败（" + fromKey + " -> " + toKey + "）: " + e.getMessage());
        }
    }

    @Override
    public boolean exists(StorageSetting setting, String objectKey) {
        return Files.isRegularFile(resolve(setting, objectKey));
    }

    @Override
    public boolean supportsList() {
        return true;
    }

    @Override
    public List<RemoteObject> list(StorageSetting setting, String prefix, int limit) {
        Path base = baseDir(setting);
        if (!Files.isDirectory(base) || limit <= 0) {
            return List.of();
        }
        String wanted = StringUtils.hasText(prefix) ? prefix.replace('\\', '/').replaceAll("^/+", "") : "";
        List<RemoteObject> result = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(base)) {
            walk.filter(Files::isRegularFile)
                    .filter(path -> !path.getFileName().toString().startsWith(TEMP_PREFIX))
                    .filter(path -> ImageTypes.isImage(path.getFileName().toString()))
                    .map(path -> Map.entry(relativeKey(base, path), path))
                    // 先按对象键排序，保证分批导入的顺序稳定（按日期目录天然有序）
                    .filter(entry -> wanted.isEmpty() || entry.getKey().startsWith(wanted))
                    .sorted(Comparator.comparing(Map.Entry::getKey))
                    .limit(limit)
                    .forEach(entry -> {
                        long size = 0L;
                        String modified = null;
                        try {
                            size = Files.size(entry.getValue());
                            modified = Files.getLastModifiedTime(entry.getValue()).toInstant().toString();
                        } catch (IOException e) {
                            log.debug("读取本地文件属性失败: {}", entry.getValue());
                        }
                        result.add(new RemoteObject(entry.getKey(), size,
                                publicUrl(setting, entry.getKey()), modified));
                    });
        } catch (IOException e) {
            throw new BizException("扫描本地目录失败: " + base + "（" + e.getMessage() + "）");
        }
        return result;
    }

    @Override
    public void test(StorageSetting setting) {
        Path base = baseDir(setting);
        try {
            Files.createDirectories(base);
            Path probe = Files.createTempFile(base, ".write-test-", ".tmp");
            Files.deleteIfExists(probe);
        } catch (IOException e) {
            throw new BizException("本地目录不可写: " + base + "（" + e.getMessage() + "）");
        }
    }

    @Override
    public String testMessage(StorageSetting setting) {
        Path base = baseDir(setting);
        return "目录可写：" + base + "（可用空间 " + readableSize(base.toFile().getUsableSpace()) + "）";
    }

    @Override
    public String publicUrl(StorageSetting setting, String objectKey) {
        Long configId = setting.configId();
        if (configId == null) {
            throw new BizException("本地存储配置尚未保存，无法生成访问地址");
        }
        String base = setting.get("publicBaseUrl");
        if (!StringUtils.hasText(base)) {
            // 配置里没写就用统一判断：当前请求地址 → app.public-base-url → 本机回环
            base = requestBase.base();
        }
        return ObjectKeys.joinUrl(base, URL_PREFIX + "/" + configId + "/" + objectKey);
    }

    /** 公开访问端点用：解析成实际存在的文件；不存在返回 null */
    public Path locateExisting(StorageSetting setting, String objectKey) {
        Path target = resolve(setting, objectKey);
        return Files.isRegularFile(target) ? target : null;
    }

    /** 存储根目录（绝对路径，已规范化） */
    public Path baseDir(StorageSetting setting) {
        String configured = setting.get("baseDir", appProperties.getLocalStorage().getBaseDir());
        if (!StringUtils.hasText(configured)) {
            configured = "uploads";
        }
        Path path = Paths.get(configured.trim());
        if (!path.isAbsolute()) {
            path = Paths.get(System.getProperty("user.dir")).resolve(path);
        }
        return path.normalize();
    }

    /** 对象键 -> 磁盘路径；拦住 ../ 之类的越界访问 */
    public Path resolve(StorageSetting setting, String objectKey) {
        String key = safeKey(objectKey);
        Path base = baseDir(setting);
        Path target = base.resolve(key).normalize();
        if (!target.startsWith(base)) {
            throw new BizException("非法的对象键: " + objectKey);
        }
        return target;
    }

    private String safeKey(String objectKey) {
        if (!StringUtils.hasText(objectKey)) {
            throw new BizException("对象键为空");
        }
        String key = objectKey.replace('\\', '/').replaceAll("^/+", "");
        for (String segment : key.split("/")) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                throw new BizException("非法的对象键: " + objectKey);
            }
        }
        return key;
    }

    private String relativeKey(Path base, Path file) {
        return base.relativize(file).toString().replace('\\', '/');
    }

    /** 删除后清理空的父目录（最多清到存储根目录为止） */
    private void pruneEmptyDirectories(StorageSetting setting, Path directory) {
        Path base = baseDir(setting);
        Path current = directory;
        while (current != null && !current.equals(base) && current.startsWith(base)) {
            try (Stream<Path> children = Files.list(current)) {
                if (children.findAny().isPresent()) {
                    return;
                }
            } catch (IOException e) {
                return;
            }
            try {
                Files.deleteIfExists(current);
            } catch (IOException e) {
                return;
            }
            current = current.getParent();
        }
    }

    private String currentRequestBase() {
        try {
            String base = ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString();
            return StringUtils.hasText(base) ? base : null;
        } catch (RuntimeException e) {
            // 后台线程（迁移、批处理）没有请求上下文
            return null;
        }
    }

    private String readableSize(long bytes) {
        if (bytes <= 0) {
            return "未知";
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
