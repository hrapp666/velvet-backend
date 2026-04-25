package com.velvet.backend.controller;

import com.velvet.backend.exception.AppException;
import com.velvet.backend.service.ChatService;
import com.velvet.backend.service.ChatService.ConversationDto;
import com.velvet.backend.service.ChatService.MessageDto;
import com.velvet.backend.service.ChatService.SendMessageRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;

    /**
     * 我的会话列表
     */
    @GetMapping("/conversations")
    public Page<ConversationDto> listConversations(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return chatService.listConversations(currentUserId(), page, size);
    }

    /**
     * 某会话的消息列表（倒序）
     */
    @GetMapping("/conversations/{id}/messages")
    public Page<MessageDto> listMessages(
            @PathVariable("id") Long conversationId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "30") int size
    ) {
        return chatService.listMessages(currentUserId(), conversationId, page, size);
    }

    /**
     * 别名路由：GET /chat/messages/{convId}
     * 兼容前端使用 /chat/messages/{id} 的旧路径
     */
    @GetMapping("/messages/{conversationId}")
    public Page<MessageDto> listMessagesAlias(
            @PathVariable Long conversationId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "30") int size
    ) {
        return chatService.listMessages(currentUserId(), conversationId, page, size);
    }

    /**
     * 发送消息（自动创建/复用会话）
     */
    @PostMapping("/messages")
    public MessageDto send(@RequestBody SendMessageRequest req) {
        return chatService.send(currentUserId(), req);
    }

    /**
     * 标记会话为已读
     */
    @PostMapping("/conversations/{id}/read")
    public void markRead(@PathVariable("id") Long conversationId) {
        chatService.markRead(currentUserId(), conversationId);
    }

    private Long currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof Long id)) {
            throw new AppException("UNAUTHORIZED", "请先登录");
        }
        return id;
    }
}
