package com.velvet.backend.service;

import com.velvet.backend.entity.Conversation;
import com.velvet.backend.entity.Message;
import com.velvet.backend.entity.User;
import com.velvet.backend.exception.AppException;
import com.velvet.backend.repository.ConversationRepository;
import com.velvet.backend.repository.MessageRepository;
import com.velvet.backend.repository.UserRepository;
import com.velvet.backend.websocket.MessageSentEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 私信服务 — 会话 + 消息
 *
 * <p>会话去重通过 (min(uid),max(uid),momentId) 唯一索引保证。
 */
@Service
@RequiredArgsConstructor
public class ChatService {

    private final ConversationRepository conversationRepo;
    private final MessageRepository messageRepo;
    private final UserRepository userRepo;
    private final NotificationService notificationService;
    private final ApplicationEventPublisher eventPublisher;
    private final ContentModerationService contentModerationService;
    private final BlockService blockService;

    // ==========================================================================
    // DTO
    // ==========================================================================
    public record ConversationDto(
            Long id,
            Long otherUserId,
            String otherUserNickname,
            String otherUserAvatarUrl,
            Long momentId,
            String lastMessageContent,
            LocalDateTime lastMessageAt,
            int unread
    ) {}

    public record MessageDto(
            Long id,
            Long conversationId,
            Long senderId,
            String senderNickname,
            String type,
            String content,
            String mediaUrl,
            Long refMomentId,
            LocalDateTime createdAt
    ) {}

    public record SendMessageRequest(
            Long otherUserId,
            Long momentId,
            String content,
            String type,
            String mediaUrl,
            Long refMomentId
    ) {}

    // ==========================================================================
    // 操作
    // ==========================================================================

    /**
     * 列出某用户的所有会话（按最后消息时间倒序）
     */
    @Transactional(readOnly = true)
    public Page<ConversationDto> listConversations(Long userId, int page, int size) {
        Pageable pageable = PageRequest.of(page, Math.min(size, 50));
        Page<Conversation> convs = conversationRepo.findAllByUser(userId, pageable);

        // 批量取对方用户信息
        var otherIds = convs.stream()
                .map(c -> c.getUserAId().equals(userId) ? c.getUserBId() : c.getUserAId())
                .collect(Collectors.toSet());
        Map<Long, User> userMap = userRepo.findAllById(otherIds).stream()
                .collect(Collectors.toMap(User::getId, u -> u));

        // 批量取最后消息内容
        var msgIds = convs.stream()
                .map(Conversation::getLastMessageId)
                .filter(id -> id != null)
                .collect(Collectors.toSet());
        Map<Long, Message> msgMap = messageRepo.findAllById(msgIds).stream()
                .collect(Collectors.toMap(Message::getId, m -> m));

        return convs.map(c -> {
            Long otherId = c.getUserAId().equals(userId) ? c.getUserBId() : c.getUserAId();
            User other = userMap.get(otherId);
            Message lastMsg = c.getLastMessageId() != null
                    ? msgMap.get(c.getLastMessageId()) : null;
            int unread = c.getUserAId().equals(userId) ? c.getUnreadA() : c.getUnreadB();
            return new ConversationDto(
                    c.getId(),
                    otherId,
                    other != null ? other.getNickname() : "(已注销)",
                    other != null ? other.getAvatarUrl() : null,
                    c.getMomentId(),
                    lastMsg != null ? truncate(lastMsg.getContent(), 60) : null,
                    c.getLastMessageAt(),
                    unread
            );
        });
    }

    /**
     * 列出某会话的消息（倒序分页）— 必须是会话参与者
     */
    @Transactional(readOnly = true)
    public Page<MessageDto> listMessages(Long userId, Long conversationId,
                                          int page, int size) {
        Conversation conv = conversationRepo.findById(conversationId)
                .orElseThrow(() -> new AppException("CONVERSATION_NOT_FOUND", "会话不存在"));
        if (!isParticipant(conv, userId)) {
            throw new AppException("FORBIDDEN", "不是此会话的参与者");
        }
        Pageable pageable = PageRequest.of(page, Math.min(size, 50));
        Page<Message> msgs = messageRepo
                .findByConversationIdOrderByCreatedAtDesc(conversationId, pageable);

        var senderIds = msgs.stream().map(Message::getSenderId)
                .collect(Collectors.toSet());
        Map<Long, User> userMap = userRepo.findAllById(senderIds).stream()
                .collect(Collectors.toMap(User::getId, u -> u));

        return msgs.map(m -> {
            User sender = userMap.get(m.getSenderId());
            return new MessageDto(
                    m.getId(),
                    m.getConversationId(),
                    m.getSenderId(),
                    sender != null ? sender.getNickname() : "(已注销)",
                    m.getType(),
                    m.getContent(),
                    m.getMediaUrl(),
                    m.getRefMomentId(),
                    m.getCreatedAt()
            );
        });
    }

    /**
     * 发送消息 — 自动创建/复用会话
     */
    @Transactional
    public MessageDto send(Long senderId, SendMessageRequest req) {
        if (req.otherUserId() == null) {
            throw new AppException("INVALID_REQUEST", "缺少接收方");
        }
        if (req.otherUserId().equals(senderId)) {
            throw new AppException("INVALID_REQUEST", "不能给自己发消息");
        }
        if (req.content() == null || req.content().isBlank()) {
            throw new AppException("INVALID_REQUEST", "消息内容不能为空");
        }
        if (req.content().length() > 2000) {
            throw new AppException("INVALID_REQUEST", "消息内容超长");
        }

        // 拉黑拦截 — 任一方向拉黑即禁止发送（不暴露具体方向）
        if (blockService.existsBetween(senderId, req.otherUserId())) {
            throw new AppException("BLOCKED", "无法发送消息");
        }

        // 内容审核 — 仅对文本消息检查（图片消息走 moderateImage 占位，v1 云审接入）
        if ("TEXT".equalsIgnoreCase(req.type()) || req.type() == null) {
            contentModerationService.moderateText(req.content(), "chat_user_" + senderId);
        }

        // 找/创建会话
        Long a = Math.min(senderId, req.otherUserId());
        Long b = Math.max(senderId, req.otherUserId());
        Conversation conv = conversationRepo.findPair(a, b, req.momentId())
                .orElseGet(() -> conversationRepo.save(Conversation.builder()
                        .userAId(a)
                        .userBId(b)
                        .momentId(req.momentId())
                        .build()));

        // 写消息
        Message msg = messageRepo.save(Message.builder()
                .conversationId(conv.getId())
                .senderId(senderId)
                .type(req.type() != null ? req.type() : "TEXT")
                .content(req.content())
                .mediaUrl(req.mediaUrl())
                .refMomentId(req.refMomentId())
                .build());

        // 更新会话 last_message + unread
        conv.setLastMessageId(msg.getId());
        conv.setLastMessageAt(msg.getCreatedAt());
        if (a.equals(senderId)) {
            conv.setUnreadB(conv.getUnreadB() + 1);
        } else {
            conv.setUnreadA(conv.getUnreadA() + 1);
        }
        conversationRepo.save(conv);

        // 发通知给对方
        try {
            User sender = userRepo.findById(senderId).orElse(null);
            String senderName = sender != null ? sender.getNickname() : "某人";
            notificationService.create(
                    req.otherUserId(),
                    "DM",
                    senderName + " 给你发了私信",
                    truncate(req.content(), 60),
                    senderId,
                    "MESSAGE",
                    msg.getId());
        } catch (Exception e) {
            // 通知失败不影响发消息
        }

        // 返回 dto
        User sender = userRepo.findById(senderId).orElse(null);
        MessageDto dto = new MessageDto(
                msg.getId(),
                conv.getId(),
                senderId,
                sender != null ? sender.getNickname() : "我",
                msg.getType(),
                msg.getContent(),
                msg.getMediaUrl(),
                msg.getRefMomentId(),
                msg.getCreatedAt()
        );

        // 触发事件 — WebSocketHandler 监听并推给对方实时
        try {
            eventPublisher.publishEvent(new MessageSentEvent(
                    senderId,
                    req.otherUserId(),
                    dto));
        } catch (Exception ignored) {}

        return dto;
    }

    /**
     * 标记会话为已读（清零未读）
     */
    @Transactional
    public void markRead(Long userId, Long conversationId) {
        Conversation conv = conversationRepo.findById(conversationId)
                .orElseThrow(() -> new AppException("CONVERSATION_NOT_FOUND", "会话不存在"));
        if (!isParticipant(conv, userId)) {
            throw new AppException("FORBIDDEN", "不是此会话的参与者");
        }
        if (conv.getUserAId().equals(userId)) {
            conv.setUnreadA(0);
        } else {
            conv.setUnreadB(0);
        }
        conversationRepo.save(conv);
    }

    private boolean isParticipant(Conversation conv, Long userId) {
        return conv.getUserAId().equals(userId) || conv.getUserBId().equals(userId);
    }

    private String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }
}
