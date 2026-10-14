package com.qxfly.imagehost.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 启用 / 禁用账号。
 */
@Data
public class UserStatusRequest {

    /** 1 启用，0 禁用 */
    @NotNull(message = "请选择要设置的状态")
    private Integer status;
}
