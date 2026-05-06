package com.huangyifei.rag.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huangyifei.rag.service.ChatHandler;
import com.huangyifei.rag.service.ChatSessionRegistry;
import com.huangyifei.rag.utils.JwtUtils;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriUtils;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

@Component
public class ChatWebSocketHandler extends TextWebSocketHandler {

    private static final String HEARTBEAT_PING = "__chat_ping__";
    private static final String HEARTBEAT_PONG = "__chat_pong__";
    private static final String INTERNAL_CMD_TOKEN = UUID.randomUUID().toString();

    private final ChatHandler chatHandler;
    private final JwtUtils jwtUtils;
    private final ChatSessionRegistry chatSessionRegistry;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ChatWebSocketHandler(ChatHandler chatHandler, JwtUtils jwtUtils, ChatSessionRegistry chatSessionRegistry) {
        this.chatHandler = chatHandler;
        this.jwtUtils = jwtUtils;
        this.chatSessionRegistry = chatSessionRegistry;
    }

    public static String getInternalCmdToken() {
        return INTERNAL_CMD_TOKEN;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String jwtToken = extractToken(session);
        if (jwtToken == null || !jwtUtils.validateToken(jwtToken)) {
            session.close(CloseStatus.POLICY_VIOLATION);
            return;
        }

        String userId = jwtUtils.extractUserIdFromToken(jwtToken);
        chatSessionRegistry.registerSession(userId, session);
        session.sendMessage(new TextMessage(objectMapper.writeValueAsString(Map.of(
                "type", "connection",
                "sessionId", session.getId(),
                "message", "WebSocket connected"
        ))));
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String payload = message.getPayload();
        if (HEARTBEAT_PING.equals(payload)) {
            session.sendMessage(new TextMessage(HEARTBEAT_PONG));
            return;
        }

        String jwtToken = extractToken(session);
        String userId = jwtUtils.extractUserIdFromToken(jwtToken);
        if (payload != null && payload.trim().startsWith("{")) {
            Map<?, ?> command = objectMapper.readValue(payload, Map.class);
            if ("stop".equals(command.get("type"))
                    && INTERNAL_CMD_TOKEN.equals(command.get("_internal_cmd_token"))
                    && command.get("generationId") != null) {
                chatHandler.stopResponse(userId, String.valueOf(command.get("generationId")));
                session.sendMessage(new TextMessage(objectMapper.writeValueAsString(Map.of(
                        "type", "stop",
                        "generationId", String.valueOf(command.get("generationId"))
                ))));
                return;
            }

            Object userMessage = command.get("message");
            if (userMessage != null) {
                Object conversationId = command.get("conversationId");
                chatHandler.processMessage(
                        userId,
                        String.valueOf(userMessage),
                        conversationId == null ? null : String.valueOf(conversationId),
                        session
                );
                return;
            }
        }

        chatHandler.processMessage(userId, payload, session);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        chatSessionRegistry.unregisterSession(session);
    }

    private String extractToken(WebSocketSession session) {
        URI uri = session.getUri();
        if (uri == null) {
            return null;
        }

        String query = uri.getQuery();
        if (query != null) {
            for (String part : query.split("&")) {
                String[] kv = part.split("=", 2);
                if (kv.length == 2 && "token".equals(kv[0])) {
                    return UriUtils.decode(kv[1], StandardCharsets.UTF_8);
                }
            }
        }

        String path = uri.getPath();
        if (path == null || !path.startsWith("/chat/")) {
            return null;
        }
        String token = path.substring("/chat/".length());
        return token.isBlank() ? null : UriUtils.decode(token, StandardCharsets.UTF_8);
    }
}
