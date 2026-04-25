package com.velvet.backend.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;
import java.util.LinkedHashMap;

/**
 * 全局异常处理 — 来自 Velvet 渗透测试 H-02 教训：
 * 错误响应绝不能泄露请求体 / stack trace / 内部字段名
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ContentViolationException.class)
    public ResponseEntity<Map<String, Object>> handleContentViolation(ContentViolationException e) {
        // 422 Unprocessable Entity — 告知客户端内容被拒，但不暴露词表细节
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", "CONTENT_VIOLATION");
        body.put("msg", e.getMessage());
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(body);
    }

    @ExceptionHandler(AppException.class)
    public ResponseEntity<Map<String, Object>> handleApp(AppException e) {
        return ResponseEntity.badRequest().body(Map.of(
                "code", e.getCode(),
                "msg", e.getMessage()
        ));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException e) {
        // ⚠️ 不要回显具体字段名 / 用户输入
        return ResponseEntity.badRequest().body(Map.of(
                "code", "VALIDATION_ERROR",
                "msg", "请求参数有误"
        ));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleAll(Exception e) {
        // 内部 log 记录详细，对外只返模糊消息
        log.error("未处理异常", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                "code", "INTERNAL_ERROR",
                "msg", "服务器开小差，稍后再试"
        ));
    }
}
