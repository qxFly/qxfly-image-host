package com.qxfly.imagehost.dto;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

/**
 * 批量下载请求：把选中的图片打包成一个 zip。
 */
@Data
public class ImageDownloadRequest {

    @NotEmpty(message = "请先选择要下载的图片")
    private List<Long> imageIds;
}
