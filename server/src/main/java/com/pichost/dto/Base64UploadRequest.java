package com.pichost.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * Base64 上传，供桌面端 / 编辑器插件等无法直接提交 multipart 的场景使用。
 */
@Data
public class Base64UploadRequest {

    /** 可以是纯 base64，也可以是 data:image/png;base64,xxx */
    @NotBlank(message = "图片内容不能为空")
    private String content;

    private String fileName = "image";

    private Long storageConfigId;
}
