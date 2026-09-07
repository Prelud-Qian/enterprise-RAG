package com.enterprise.rag.common;

import lombok.Data;

/**
 * 统一返回结果封装
 * code 与 HTTP 状态码一致：200 成功 / 400 业务错误 / 401 未登录 / 403 无权限 / 404 不存在 / 500 系统错误
 */
@Data
public class Result<T> {

    private Integer code;
    private String message;
    private T data;

    public static <T> Result<T> ok(T data) {
        Result<T> r = new Result<>();
        r.code = 200;
        r.message = "success";
        r.data = data;
        return r;
    }

    public static Result<Void> ok() {
        return ok(null);
    }

    public static <T> Result<T> fail(int code, String message) {
        Result<T> r = new Result<>();
        r.code = code;
        r.message = message;
        return r;
    }
}
