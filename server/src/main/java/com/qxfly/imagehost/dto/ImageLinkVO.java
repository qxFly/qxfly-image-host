package com.qxfly.imagehost.dto;

import lombok.Data;

/**
 * 一张图片可用的某一种链接（用于「选择复制哪种链接」的弹窗）。
 */
@Data
public class ImageLinkVO {

    private String key;
    private String name;
    private String url;
    /** Markdown 粘贴语法 */
    private String markdown;
    /** 原始地址（存储后端直接给的地址） */
    private boolean origin;
    /** 服务端代理下载（url 为空，前端调下载接口） */
    private boolean proxy;
    private boolean builtin;
    private boolean previewDefault;
    private boolean downloadDefault;
    private boolean copyDefault;
}
