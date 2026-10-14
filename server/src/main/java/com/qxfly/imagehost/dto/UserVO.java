package com.qxfly.imagehost.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class UserVO {

    private Long id;
    private String username;
    private String nickname;
    private String email;
    /** 归一化后的角色名：SUPER_ADMIN / USER */
    private String role;
    /** 角色的中文名：超级管理员 / 普通用户 */
    private String roleLabel;
    private Integer status;
    private LocalDateTime createdAt;
}
