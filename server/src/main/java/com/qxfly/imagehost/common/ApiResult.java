package com.qxfly.imagehost.common;

import lombok.Data;

import java.io.Serializable;

/**
 * 统一响应结构。
 *
 * @param <T> 业务数据类型
 */
@Data
public class ApiResult<T> implements Serializable {

    /** 200 表示成功，其它值表示业务/系统异常 */
    private int code;
    private String message;
    private T data;
    private long timestamp = System.currentTimeMillis();

    public static <T> ApiResult<T> ok() {
        return ok(null);
    }

    public static <T> ApiResult<T> ok(T data) {
        ApiResult<T> result = new ApiResult<>();
        result.setCode(200);
        result.setMessage("ok");
        result.setData(data);
        return result;
    }

    public static <T> ApiResult<T> fail(int code, String message) {
        ApiResult<T> result = new ApiResult<>();
        result.setCode(code);
        result.setMessage(message);
        return result;
    }
}
