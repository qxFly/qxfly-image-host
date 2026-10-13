package com.pichost.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 临时功能：把 GitHub 仓库某个目录下的图片文件按系统存储规则搬到另一个目录。
 * 仅支持 GitHub（同仓库移动可以直接复用原有 blob，不需要下载再上传）。
 */
@Data
public class RepoMoveRequest {

    @NotNull(message = "请选择存储配置")
    private Long storageConfigId;

    /** 源目录（相对仓库根），默认 api */
    private String sourceDirectory = "api";

    /** 目标目录（相对仓库根），默认 api/images */
    private String targetDirectory = "api/images";

    /** 是否包含子目录；false 时只处理直接放在源目录下的文件 */
    private boolean includeSubdirectories = false;

    /** 保留原文件名（默认 true，即只换目录、不改名）；false 时改用 uuid 重新命名 */
    private boolean keepFileName = true;

    /** 是否再套一层 日期/分片 目录；false 时文件直接放进目标目录 */
    private boolean layered = false;

    /** 本次最多搬多少个文件（0 = 不限制）。分批执行时用，每批搬完就提交，失败只损失这一批 */
    private int maxFiles = 0;

    /** 跳过前多少个（按对象键排序）。整理分片时用：留在原地前 N 个，只把超出的部分搬走 */
    private int skipFirst = 0;

    /** true 只预览不提交 */
    private boolean dryRun = true;
}
