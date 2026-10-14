package com.qxfly.imagehost.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ProfileRequest {

    @Size(max = 64, message = "昵称最长 64 个字符")
    private String nickname;

    @Email(message = "邮箱格式不正确")
    private String email;
}
