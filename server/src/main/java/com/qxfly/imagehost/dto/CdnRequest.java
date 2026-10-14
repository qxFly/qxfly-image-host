package com.qxfly.imagehost.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 新增 / 修改自定义 CDN。
 */
@Data
public class CdnRequest {

    @NotBlank(message = "名称不能为空")
    @Size(max = 64, message = "名称最长 64 个字符")
    private String name;

    /** 地址前缀，可用 {owner} / {repo} / {branch} 占位符 */
    @NotBlank(message = "地址前缀不能为空")
    @Size(max = 512, message = "地址前缀最长 512 个字符")
    private String template;

    private Boolean enabled = Boolean.TRUE;
}
