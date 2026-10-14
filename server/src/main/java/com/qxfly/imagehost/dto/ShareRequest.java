package com.qxfly.imagehost.dto;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

/** 创建 / 修改分享链接 */
@Data
public class ShareRequest {

    private String title;

    @NotEmpty(message = "请先选择要分享的图片")
    private List<Long> imageIds;

    /** 访问密码，留空表示不需要密码 */
    private String password;

    /** 有效天数，空或 0 表示长期有效 */
    private Integer expiresInDays;
}
