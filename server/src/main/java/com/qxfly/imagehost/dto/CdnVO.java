package com.qxfly.imagehost.dto;

import lombok.Data;

/**
 * 一个 CDN 选项。
 */
@Data
public class CdnVO {

    /** origin 表示原始地址；其余为内置预设 key 或自定义项的 key */
    private String key;
    private String name;
    /** 前缀模板（可能带 {owner} / {repo} / {branch} 占位符） */
    private String template;
    /** 用样例对象键拼出来的真实地址，前端直接拿它测速 */
    private String example;
    /** 是否是原始地址（存储后端直链） */
    private boolean origin;
    /** 是否是系统预设（预设不可删除、不可改模板） */
    private boolean builtin;
    private boolean enabled;
    private boolean previewDefault;
    private boolean downloadDefault;
    private boolean copyDefault;
}
