package com.qxfly.imagehost.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 超级管理员修改某个账号的资料（用户名不可改，角色更不可改）。
 */
@Data
public class UserUpdateRequest {

    @Size(max = 64, message = "昵称最长 64 个字符")
    private String nickname;

    @Email(message = "邮箱格式不正确")
    private String email;
}
