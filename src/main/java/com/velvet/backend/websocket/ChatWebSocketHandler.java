package com.velvet.backend.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.velvet.backend.security.JwtUtils;
import com.velvet.backend.service.ChatService;
import com.velvet.backend.service.ChatService.MessageDto;
import com.velvet.backend.service.ChatService.SendMessageRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Velvet 私聊 WebSocket Handler
 *
 * <p>协议：
 * <ul>
 *   <li>connect: wss://api.rvqu4vaz.work/ws/chat ; Sec-WebSocket-Protocol: velvet.token.&lt;JWT&gt;</li>
 *   <li>send msg: {"type":"MSG","conversationId":1,"content":"hi","msgType":"TEXT"}</li>
 *   <li>typing:   {"type":"TYPING","conversationId":1}</li>
 *   <li>read:     {"type":"READ","conversationId":1,"messageId":99}</li>
 *   <li>ping:     {"type":"PING"}</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatWebSocketHandler extends TextWebSocketHandler {

    private final JwtUtils jwtUtils;
    private final ChatService chatService;
    private final ObjectMapper json = new ObjectMapper();

    /** userId → session */
    private final Map<Long, WebSocketSession> sessions = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        Long userId = extractUserId(session);
        if (userId == null) {
            session.close(CloseStatus.POLICY_VIOLATION.withReason("Missing or invalid token"));
            return;
        }
        sessions.put(userId, session);
        log.info("WebSocket connected: userId={}, total={}", userId, sessions.size());

        sendJson(session, Map.of(
                "type", "CONNECTED",
                "userId", userId,
                "timestamp", Instant.now().toEpochMilli()
        ));
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        Long fromUserId = (Long) session.getAttributes().get("userId");
        if (fromUserId == null) {
            session.close(CloseStatus.POLICY_VIOLATION);
            return;
        }

        try {
            Map<String, Object> payload = json.readValue(message.getPayload(), Map.class);
            String type = (String) payload.get("type");

            switch (type) {
                case "PING" -> sendJson(session, Map.of(
                        "type", "PONG",
                        "timestamp", Instant.now().toEpochMilli()
                ));
                case "MSG" -> handleSendMessage(fromUserId, payload);
                case "TYPING" -> handleTyping(fromUserId, payload);
                case "READ" -> handleRead(fromUserId, payload);
                default -> log.warn("Unknown message type from userId={}: {}", fromUserId, type);
            }
        } catch (Exception e) {
            log.error("WebSocket message error from userId={}", fromUserId, e);
            sendJson(session, Map.of(
                    "type", "ERROR",
                    "msg", "消息处理失败"
            ));
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Long userId = (Long) session.getAttributes().get("userId");
        if (userId != null) {
            sessions.remove(userId);
            log.info("WebSocket closed: userId={}, status={}, remaining={}",
                    userId, status, sessions.size());
        }
    }

    // ─── 业务处理 ────────────────────────────────────────

    private void handleSendMessage(Long fromUserId, Map<String, Object> payload) {
        // 协议: { type:"MSG", otherUserId:N, content:"...", momentId?:N, msgType?:"TEXT" }
        Object otherIdRaw = payload.get("otherUserId");
        if (otherIdRaw == null) {
            log.warn("WS MSG missing otherUserId from userId={}", fromUserId);
            return;
        }
        Long otherUserId = ((Number) otherIdRaw).longValue();
        String content = (String) payload.get("content");
        String msgType = (String) payload.getOrDefault("msgType", "TEXT");
        Long momentId = payload.get("momentId") != null
                ? ((Number) payload.get("momentId")).longValue() : null;

        try {
            // 真持久化 — ChatService.send 会发布 MessageSentEvent，
            // 我们的 @EventListener 会自动推给对方 + 发送方
            chatService.send(fromUserId, new SendMessageRequest(
                    otherUserId, momentId, content, msgType, null, null));
        } catch (Exception e) {
            log.error("WS send failed: userId={}, otherUserId={}", fromUserId, otherUserId, e);
            sendJsonToUser(fromUserId, Map.of(
                    "type", "ERROR",
                    "msg", e.getMessage() != null ? e.getMessage() : "发送失败"
            ));
        }
    }

    /**
     * 监听 ChatService 发布的事件 — 推给双方实时
     */
    @EventListener
    public void onMessageSent(MessageSentEvent event) {
        MessageDto msg = event.message();
        Map<String, Object> outgoing = Map.of(
                "type", "MSG",
                "id", msg.id(),
                "conversationId", msg.conversationId(),
                "senderId", msg.senderId(),
                "senderNickname", msg.senderNickname() != null ? msg.senderNickname() : "",
                "content", msg.content(),
                "msgType", msg.type() != null ? msg.type() : "TEXT",
                "timestamp", Instant.now().toEpochMilli()
        );
        // 推给对方
        sendJsonToUser(event.toUserId(), outgoing);
        // 推给发送方（确认）
        sendJsonToUser(event.fromUserId(), outgoing);
    }

    private void handleTyping(Long fromUserId, Map<String, Object> payload) {
        Long conversationId = ((Number) payload.get("conversationId")).longValue();
        // TODO: 推给对方 TYPING 事件
        log.debug("typing: userId={}, conversationId={}", fromUserId, conversationId);
    }

    private void handleRead(Long fromUserId, Map<String, Object> payload) {
        Long conversationId = ((Number) payload.get("conversationId")).longValue();
        // TODO: 标记 messages 已读 + 推给对方
        log.debug("read: userId={}, conversationId={}", fromUserId, conversationId);
    }

    // ─── 工具 ────────────────────────────────────────────

    private Long extractUserId(WebSocketSession session) {
        // P1-3:token 走 Sec-WebSocket-Protocol 子协议(`velvet.token.<JWT>`),
        // 由 TokenSubProtocolHandshakeHandler 负责协商,这里读 acceptedProtocol。
        String accepted = session.getAcceptedProtocol();
        String token = null;
        if (accepted != null && accepted.startsWith("velvet.token.")) {
            token = accepted.substring("velvet.token.".length());
        }
        if (token == null || token.isEmpty()) return null;
        Long userId = jwtUtils.parseUserId(token);
        if (userId != null) {
            session.getAttributes().put("userId", userId);
        }
        return userId;
    }

    private void sendJson(WebSocketSession session, Map<String, Object> data) {
        try {
            if (session.isOpen()) {
                session.sendMessage(new TextMessage(json.writeValueAsString(data)));
            }
        } catch (Exception e) {
            log.error("Failed to send WebSocket message", e);
        }
    }

    private void sendJsonToUser(Long userId, Map<String, Object> data) {
        WebSocketSession session = sessions.get(userId);
        if (session != null) sendJson(session, data);
    }
}
