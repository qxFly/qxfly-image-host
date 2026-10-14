package com.qxfly.imagehost.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class RegisterRequest {

    @NotBlank(message = "用户名不能为空")
    @Pattern(regexp = "^[a-zA-Z0-9_-]{3,32}$", message = "只能是 3-32 位字母、数字、下划线或短横线")
    private String username;

    @NotBlank(message = "密码不能为空")
    @Size(min = 6, max = 64, message = "长度需在 6-64 位之间")
    private String password;

    @Size(max = 64, message = "昵称最长 64 个字符")
    private String nickname;

    @Email(message = "邮箱格式不正确")
    private String email;
}
