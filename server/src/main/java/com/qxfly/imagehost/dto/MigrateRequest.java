package com.qxfly.imagehost.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/**
 * 批量迁移请求。
 * 迁移范围二选一：指定 imageIds，或勾选 migrateAll + 筛选条件（迁移全部）。
 */
@Data
public class MigrateRequest {

    @NotNull(message = "请选择目标存储配置")
    private Long targetStorageConfigId;

    /** 目标路径前缀，留空则用目标配置自己的 pathPrefix */
    private String targetPrefix;

    /** true = 保留源文件的目录结构（相对源配置的路径前缀） */
    private boolean preserveDirectory = false;

    /** true = 保留源文件名；false = 用 uuid 重新命名 */
    private boolean preserveFileName = false;

    /** 迁移成功后是否删除源文件（跨存储且键不同时才删） */
    private boolean deleteSource = false;

    /** 指定要迁移的图片 ID */
    private List<Long> imageIds;

    /** 按来源存储筛选 */
    private Long sourceStorageConfigId;

    /** 按文件名 / 对象键模糊筛选 */
    private String keyword;

    /** 必须显式置 true 才允许在没有 imageIds 时迁移整个筛选结果 */
    private boolean migrateAll = false;
}
