package com.qxfly.imagehost.dto;

import lombok.Data;

/**
 * 设置预览 / 下载 / 复制各自使用的默认 CDN。
 * 值为 CDN 的 key，留空或 origin 表示使用原始地址。
 */
@Data
public class CdnDefaultsRequest {

    private String preview;
    private String download;
    private String copy;
}
