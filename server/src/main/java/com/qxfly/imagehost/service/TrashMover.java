package com.qxfly.imagehost.service;

import com.qxfly.imagehost.common.BizException;
import com.qxfly.imagehost.config.AppProperties;
import com.qxfly.imagehost.storage.StorageService;
import com.qxfly.imagehost.storage.StorageServiceFactory;
import com.qxfly.imagehost.storage.StorageSetting;
import com.qxfly.imagehost.storage.StorageType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 回收站的「仓库侧」处理。
 * <p>
 * 只在数据库里打标记的话，仓库里的文件还留在图库目录，看起来并没有被删掉。
 * 所以逻辑删除时顺便把文件搬进一个独立的回收站目录（默认 {@code _trash/}），恢复时再搬回来：
 * 复用 {@link GithubTreeMover} 的<b>引用式搬移</b> —— 新 tree 直接引用原有 blob 的 SHA，
 * 不下载不上传、不产生重复文件，一次搬运只多一个提交。
 * <p>
 * 只有 GitHub 的 contents 模式支持这种同仓库搬移；S3 / Release 附件等没有这个概念，
 * 这些后端退化成「只打数据库标记，文件原地保留」，物理删除时才真正删掉。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TrashMover {

    private final GithubTreeMover treeMover;
    private final StorageServiceFactory storageServiceFactory;
    private final AppProperties appProperties;

    /** 回收站目录前缀（仓库根目录下），去掉首尾斜杠 */
    public String prefix() {
        String value = appProperties.getTrash().getPrefix();
        if (!StringUtils.hasText(value)) {
            return "_trash";
        }
        String normalized = value.trim().replaceAll("^/+", "").replaceAll("/+$", "");
        return normalized.isEmpty() ? "_trash" : normalized;
    }

    /**
     * 该存储后端能不能把文件搬进回收站目录。
     * <p>
     * GitHub contents 模式走仓库内的引用式搬移；本地存储直接是磁盘改名；
     * 其他后端（S3 / OSS / Release 附件 ...）没有「移动」这个概念，
     * 退化成「只打数据库标记，文件原地保留」，物理删除时才真正删掉。
     */
    public boolean supports(StorageSetting setting) {
        if (setting == null) {
            return false;
        }
        if (isGithubContents(setting)) {
            return true;
        }
        return storageServiceFactory.get(setting.type()).supportsMove();
    }

    /** GitHub contents 模式：走仓库引用式搬移 */
    private boolean isGithubContents(StorageSetting setting) {
        return setting.type() == StorageType.GITHUB
                && "contents".equalsIgnoreCase(nvl(setting.get("mode"), "contents"));
    }

    /** 对象键在回收站目录里的位置 */
    public String trashKey(String objectKey) {
        return prefix() + "/" + objectKey;
    }

    /** 这个对象键是不是回收站目录里的 */
    public boolean isTrashKey(String objectKey) {
        if (!StringUtils.hasText(objectKey)) {
            return false;
        }
        String prefix = prefix() + "/";
        return objectKey.startsWith(prefix);
    }

    /**
     * 把远端文件搬进回收站目录。
     *
     * @return 搬过去之后的对象键；不支持或失败返回 null（调用方退化为只打数据库标记）
     */
    public String moveToTrash(StorageSetting setting, String objectKey) {
        if (!supports(setting) || !StringUtils.hasText(objectKey)) {
            return null;
        }
        String target = trashKey(objectKey);
        if (!isGithubContents(setting)) {
            return moveInStorage(setting, objectKey, target);
        }
        try {
            String sha = treeMover.blobShaOrNull(setting, objectKey);
            if (sha == null) {
                // 原位置已经没有了：可能之前就搬过
                return treeMover.blobShaOrNull(setting, target) != null ? target : null;
            }
            if (treeMover.blobShaOrNull(setting, target) != null) {
                // 回收站目录里有同名文件（正常不会发生），先清掉再搬，避免被当成重名跳过
                log.warn("回收站目录已存在 {}，先删除旧的", target);
                treeMover.delete(setting, List.of(target), null);
            }
            List<GithubTreeMover.Item> moved = treeMover.move(setting,
                    List.of(new GithubTreeMover.Item(objectKey, target, sha)), null);
            if (moved.isEmpty()) {
                log.warn("搬到回收站目录没有生效: {} -> {}", objectKey, target);
                return null;
            }
            log.info("已把 {} 搬进回收站目录 {}", objectKey, target);
            return target;
        } catch (Exception e) {
            log.warn("搬到回收站目录失败（{} -> {}），只打数据库标记: {}", objectKey, target, e.getMessage());
            return null;
        }
    }

    /**
     * 恢复：把文件从回收站目录搬回原位置。
     * <p>
     * 幂等：如果回收站目录里已经没有了、而原位置有文件，就当作已经搬回去过，直接返回。
     */
    public void restore(StorageSetting setting, String objectKey, String trashKey) {
        if (!StringUtils.hasText(trashKey)) {
            return;
        }
        if (!supports(setting)) {
            throw new BizException("当前存储后端不支持从回收站目录搬回，请手工处理：" + trashKey);
        }
        if (!isGithubContents(setting)) {
            restoreInStorage(setting, objectKey, trashKey);
            return;
        }
        String sha = treeMover.blobShaOrNull(setting, trashKey);
        if (sha == null) {
            if (treeMover.blobShaOrNull(setting, objectKey) != null) {
                log.info("{} 已经不在回收站目录里，跳过搬回", objectKey);
                return;
            }
            throw new BizException("回收站目录里找不到该文件：" + trashKey);
        }
        if (treeMover.blobShaOrNull(setting, objectKey) != null) {
            throw new BizException("原位置已经有同名文件，无法搬回：" + objectKey);
        }
        treeMover.move(setting, List.of(new GithubTreeMover.Item(trashKey, objectKey, sha)), null);
        log.info("已把 {} 从回收站目录搬回 {}", trashKey, objectKey);
    }

    /**
     * 支持「存储内部移动」的后端（本地存储）：直接在原地改名，零流量。
     * 幂等：原位置已经不在了、回收站里有，就当作已经搬过。
     */
    private String moveInStorage(StorageSetting setting, String objectKey, String target) {
        StorageService service = storageServiceFactory.get(setting.type());
        try {
            if (!service.exists(setting, objectKey)) {
                return service.exists(setting, target) ? target : null;
            }
            if (service.exists(setting, target)) {
                log.warn("回收站目录已存在 {}，先删除旧的", target);
                service.delete(setting, target);
            }
            service.move(setting, objectKey, target);
            log.info("已把 {} 搬进回收站目录 {}", objectKey, target);
            return target;
        } catch (Exception e) {
            log.warn("搬到回收站目录失败（{} -> {}），只打数据库标记: {}", objectKey, target, e.getMessage());
            return null;
        }
    }

    private void restoreInStorage(StorageSetting setting, String objectKey, String trashKey) {
        StorageService service = storageServiceFactory.get(setting.type());
        if (!service.exists(setting, trashKey)) {
            if (service.exists(setting, objectKey)) {
                log.info("{} 已经不在回收站目录里，跳过搬回", objectKey);
                return;
            }
            throw new BizException("回收站目录里找不到该文件：" + trashKey);
        }
        if (service.exists(setting, objectKey)) {
            throw new BizException("原位置已经有同名文件，无法搬回：" + objectKey);
        }
        service.move(setting, trashKey, objectKey);
        log.info("已把 {} 从回收站目录搬回 {}", trashKey, objectKey);
    }

    private String nvl(String value, String fallback) {
        return StringUtils.hasText(value) ? value : fallback;
    }
}
