package com.qxfly.imagehost.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 一组重复图片（内容哈希相同）。
 */
@Data
public class DuplicateGroupVO {

    private final String hash;
    private final long count;
    private final long totalSize;
    private final List<DuplicateImage> images;

    /** 组内的一张图 */
    public record DuplicateImage(Long id, String originalName, String objectKey, String url, Long size,
                                 LocalDateTime createdAt, Long storageConfigId, boolean keep) {
    }
}
