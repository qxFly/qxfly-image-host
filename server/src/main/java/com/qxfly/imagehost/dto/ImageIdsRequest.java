package com.qxfly.imagehost.dto;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

/**
 * 批量操作（批量删除 / 回收站恢复 / 彻底删除）请求。
 */
@Data
public class ImageIdsRequest {

    @NotEmpty(message = "请先选择要操作的图片")
    private List<Long> imageIds;
}
