package com.pichost.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 重复文件清理（仅 GitHub）。
 */
@Data
public class RepoDedupeRequest {

    @NotNull(message = "请选择存储配置")
    private Long storageConfigId;

    /** 只在这个目录下找重复（默认 api/images） */
    private String directory = "api/images";

    /** true 只分析不删除 */
    private boolean dryRun = true;

    /** true 时连"内容不同但名字带 -N 后缀"的也删（默认 false，只删确认为真重复的） */
    private boolean forceDeleteSuffixed = false;
}
