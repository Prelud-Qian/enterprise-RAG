package com.enterprise.rag.common;

import lombok.Getter;

/**
 * 业务异常：code 使用 HTTP 状态码语义（400/403/404/500）
 */
@Getter
public class BusinessException extends RuntimeException {

    private final int code;

    public BusinessException(String message) {
        this(400, message);
    }

    public BusinessException(int code, String message) {
        super(message);
        this.code = code;
    }
}
