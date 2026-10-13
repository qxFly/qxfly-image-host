package com.pichost.dto;

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

    /** Markdown 粘贴语法 */
    public String getMarkdown() {
        return "![" + (originalName == null ? "" : originalName) + "](" + url + ")";
    }
}
