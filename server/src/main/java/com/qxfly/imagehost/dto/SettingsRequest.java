package com.qxfly.imagehost.dto;

import lombok.Data;

/**
 * 系统设置更新请求。两个字段都可为空：只提交要改的那一项。
 */
@Data
public class SettingsRequest {

    /** 单张文件大小上限（字节）；配合 unlimitedUploadSize=false 使用 */
    private Long maxUploadSize;

    /** 是否不限制单张文件大小；true 时忽略 maxUploadSize */
    private Boolean unlimitedUploadSize;

    /** 登录失败几次后锁定（1 ~ 100） */
    private Integer loginMaxAttempts;

    /** 锁定时长（分钟，1 ~ 1440） */
    private Integer loginLockMinutes;

    /** 防盗链开关 */
    private Boolean hotlinkEnabled;

    /** 防盗链白名单（逗号分隔） */
    private String hotlinkDomains;

    /** 没有 Referer 时是否放行 */
    private Boolean hotlinkAllowEmpty;
}
