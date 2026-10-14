package com.qxfly.imagehost.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ImageVO {

    private Long id;
    private String originalName;
    private String objectKey;
    /** 文件所在目录（对象键去掉文件名），根目录下为空串 */
    private String directory;
    /** 目录层级，便于前端缩进展示 */
    private int depth;
    private String url;
    /** 预览使用的地址（按「预览默认 CDN」解析，未配置就是 url） */
    private String previewUrl;
    /** 下载使用的地址（按「下载默认 CDN」解析） */
    private String downloadUrl;
    /** 复制链接使用的地址（按「复制默认 CDN」解析） */
    private String copyUrl;
    private String thumbUrl;
    private String mimeType;
    private Long size;
    private Integer width;
    private Integer height;
    private String storageType;
    private String storageTypeLabel;
    private Long storageConfigId;
    private String storageConfigName;
    private LocalDateTime createdAt;
    /** 移入回收站的时间，正常图片为 null */
    private LocalDateTime deletedAt;
    /** 在回收站目录里的对象键；为空说明云端文件还留在原位 */
    private String trashKey;

    /** Markdown 粘贴语法 */
    public String getMarkdown() {
        return "![" + (originalName == null ? "" : originalName) + "](" + url + ")";
    }
}
