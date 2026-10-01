package com.enterprise.rag.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 全局异常处理：所有异常统一转 Result 结构，HTTP 状态码与业务 code 一致
 * <p>
 * 每个响应都显式指定 application/json：@RequestMapping(produces=text/event-stream)
 * 的接口（如 /ask/stream）遇到同步抛出的异常时，若不预设媒体类型，
 * 会因请求头 Accept: text/event-stream 无法协商出 JSON 而变成 500 空响应体。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Result<Void>> handleBusiness(BusinessException e) {
        HttpStatus status = HttpStatus.resolve(e.getCode());
        return ResponseEntity.status(status != null ? status : HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Result.fail(e.getCode(), e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Result<Void>> handleValid(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(f -> f.getField() + " " + f.getDefaultMessage())
                .orElse("参数校验失败");
        return ResponseEntity.badRequest().contentType(MediaType.APPLICATION_JSON)
                .body(Result.fail(400, msg));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Result<Void>> handleUploadSize(MaxUploadSizeExceededException e) {
        return ResponseEntity.badRequest().contentType(MediaType.APPLICATION_JSON)
                .body(Result.fail(400, "上传文件大小超出限制"));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Result<Void>> handleNotFound(NoResourceFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON)
                .body(Result.fail(404, "接口不存在"));
    }

    /** 客户端主动断开（刷新页面/停止生成）导致 SSE 写失败：连接已不可用、无响应可写，降为 DEBUG 免刷 ERROR 日志 */
    @ExceptionHandler(AsyncRequestNotUsableException.class)
    public void handleClientAbort(AsyncRequestNotUsableException e) {
        log.debug("客户端已断开，忽略异步响应写入失败: {}", e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleOther(Exception e) {
        log.error("系统异常", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Result.fail(500, "系统内部错误：" + e.getMessage()));
    }
}
