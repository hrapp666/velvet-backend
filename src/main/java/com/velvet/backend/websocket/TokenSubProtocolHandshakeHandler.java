package com.velvet.backend.websocket;

import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.support.DefaultHandshakeHandler;

import java.util.List;

/**
 * 协商 Sec-WebSocket-Protocol — 接受任意 `velvet.token.<JWT>` 形式的子协议,
 * 让浏览器/客户端不能用 querystring 传 token 也能完成 RFC 6455 子协议握手。
 *
 * <p>真正的鉴权仍在 {@link ChatWebSocketHandler#afterConnectionEstablished} 里,
 * 这里只做"echo 回客户端要求的子协议"以满足握手协议。</p>
 */
public class TokenSubProtocolHandshakeHandler extends DefaultHandshakeHandler {

    private static final String PREFIX = "velvet.token.";

    @Override
    protected String selectProtocol(List<String> requestedProtocols, WebSocketHandler handler) {
        if (requestedProtocols == null) {
            return null;
        }
        for (String p : requestedProtocols) {
            if (p != null && p.startsWith(PREFIX) && p.length() > PREFIX.length()) {
                return p;
            }
        }
        return null;
    }
}
