package com.qxfly.imagehost.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/** 分享链接（管理端列表用，不回传密码） */
@Data
public class ShareVO {

    private Long id;
    private String token;
    private String title;
    /** 对外访问地址（hash 路由，直接拼域名即可） */
    private String url;
    private boolean passwordProtected;
    private LocalDateTime expiresAt;
    private boolean enabled;
    private boolean expired;
    private Long viewCount;
    private int imageCount;
    private LocalDateTime createdAt;
    /** 公开访问时才带图片明细 */
    private List<ImageVO> images;
}
