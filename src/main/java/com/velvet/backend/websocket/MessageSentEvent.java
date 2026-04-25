package com.velvet.backend.websocket;

import com.velvet.backend.service.ChatService.MessageDto;

/**
 * 消息发送事件 — ChatService 发送后触发，由 ChatWebSocketHandler 监听并推给对方
 *
 * <p>用 Spring ApplicationEvent 解耦，避免 ChatService ↔ WebSocketHandler 循环依赖。
 */
public record MessageSentEvent(
        Long fromUserId,
        Long toUserId,
        MessageDto message
) {}
