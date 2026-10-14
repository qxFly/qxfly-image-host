package com.qxfly.imagehost.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 超级管理员重置某个账号的密码。
 */
@Data
public class UserResetPasswordRequest {

    @NotBlank(message = "新密码不能为空")
    @Size(min = 6, max = 64, message = "新密码长度需在 6-64 位之间")
    private String newPassword;
}
