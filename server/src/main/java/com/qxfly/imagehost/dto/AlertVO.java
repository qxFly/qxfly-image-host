package com.qxfly.imagehost.dto;

import lombok.Data;

/**
 * 概览页的可行动提示（有东西该处理时才出现）。
 */
@Data
public class AlertVO {

    /** info | warning | danger */
    private String level;
    private String title;
    private String detail;
    /** 点击后跳转的前端路由 */
    private String path;
}
