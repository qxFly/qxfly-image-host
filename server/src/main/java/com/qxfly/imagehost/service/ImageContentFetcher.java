package com.qxfly.imagehost.service;

import com.qxfly.imagehost.common.BizException;
import com.qxfly.imagehost.entity.ImageEntity;
import com.qxfly.imagehost.storage.StorageService;
import com.qxfly.imagehost.storage.StorageServiceFactory;
import com.qxfly.imagehost.storage.StorageSetting;
import com.qxfly.imagehost.storage.support.HttpSupport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Locale;
import java.util.Map;

/**
 * 读取图片的原始字节：优先按对象键下载（私有仓库 / 私有桶也能用），失败再回退到已保存的直链。
 * <p>
 * 批量迁移和下载共用这段逻辑，避免两处各写一份、改一处漏一处。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ImageContentFetcher {

    private final StorageServiceFactory storageServiceFactory;

    /**
     * 取回单个对象的原始内容。
     *
     * @throws BizException 对象键和直链都取不到内容时
     */
    public byte[] fetch(ImageEntity image, StorageSetting setting) {
        StorageService service = storageServiceFactory.get(setting.type());
        if (service.supportsDownload() && StringUtils.hasText(image.getObjectKey())) {
            try {
                byte[] content = service.download(setting, image.getObjectKey());
                if (content != null && content.length > 0) {
                    return content;
                }
            } catch (Exception e) {
                // 按对象键下载不可用时回退直链（例如私有桶没配 AK/SK 的副本场景）
                if (isRetryable(e)) {
                    throw e instanceof RuntimeException ? (RuntimeException) e : new BizException(e.getMessage());
                }
                log.debug("按对象键下载失败（回退直链）: {}", e.getMessage());
            }
        }
        if (!StringUtils.hasText(image.getUrl())) {
            throw new BizException("该图片没有可用的访问地址，无法读取原始内容");
        }
        HttpSupport.BinaryResult result = HttpSupport.getBinary(image.getUrl(), Map.of());
        if (result.status() < 200 || result.status() >= 300) {
            throw new BizException("下载源文件失败 (HTTP " + result.status() + ")：" + image.getUrl());
        }
        if (result.body() == null || result.body().length == 0) {
            throw new BizException("下载到的文件为空：" + image.getUrl());
        }
        return result.body();
    }

    /**
     * 限流 / 5xx / 超时属于「等一会儿再试就好了」的错误。
     * 调用方据此决定是退避重试，还是直接回退到直链。
     */
    public static boolean isRetryable(Throwable e) {
        String message = e.getMessage();
        if (message == null) {
            return false;
        }
        String text = message.toLowerCase(Locale.ROOT);
        return text.contains("rate limit")
                || text.contains("secondary rate")
                || text.contains("http 403")
                || text.contains("http 429")
                || text.contains("http 500")
                || text.contains("http 502")
                || text.contains("http 503")
                || text.contains("http 504")
                || text.contains("timed out")
                || text.contains("timeout")
                || text.contains("connection reset");
    }
}
