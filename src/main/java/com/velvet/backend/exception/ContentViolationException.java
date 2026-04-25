package com.velvet.backend.exception;

/**
 * 内容违规异常 — 触发时 GlobalExceptionHandler 返回 422。
 * <p>
 * 消息面向用户，不暴露具体触发词，仅给出模糊提示。
 * 审计日志在 ContentModerationService 里单独 log.warn。
 */
public class ContentViolationException extends RuntimeException {

    public ContentViolationException(String message) {
        super(message);
    }
}
