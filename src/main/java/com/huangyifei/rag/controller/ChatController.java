package com.huangyifei.rag.controller;

import com.huangyifei.rag.handler.ChatWebSocketHandler;
import com.huangyifei.rag.service.ChatGenerationStateService;
import com.huangyifei.rag.utils.JwtUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/chat")
public class ChatController {

    private final JwtUtils jwtUtils;
    private final ChatGenerationStateService chatGenerationStateService;

    public ChatController(JwtUtils jwtUtils, ChatGenerationStateService chatGenerationStateService) {
        this.jwtUtils = jwtUtils;
        this.chatGenerationStateService = chatGenerationStateService;
    }

    @GetMapping("/websocket-token")
    public ResponseEntity<?> getWebSocketToken(@RequestHeader("Authorization") String token) {
        if (token == null || !token.startsWith("Bearer ")) {
            return ResponseEntity.status(401).body(responseBody(401, "Invalid token", null));
        }
        String jwtToken = token.replace("Bearer ", "");
        if (!jwtUtils.validateToken(jwtToken)) {
            return ResponseEntity.status(401).body(responseBody(401, "Invalid token", null));
        }

        String cmdToken = ChatWebSocketHandler.getInternalCmdToken();
        if (cmdToken == null || cmdToken.trim().isEmpty()) {
            return ResponseEntity.status(500).body(responseBody(500, "WebSocket token is not available", null));
        }

        return ResponseEntity.ok(responseBody(200, "获取 WebSocket Token 成功", Map.of("cmdToken", cmdToken)));
    }

    @GetMapping("/generation/{generationId}")
    public ResponseEntity<?> getGeneration(
            @PathVariable String generationId,
            @RequestHeader("Authorization") String token) {
        String userId = extractValidatedUserId(token);
        if (userId == null) {
            return ResponseEntity.status(401).body(responseBody(401, "Invalid token", null));
        }

        return ResponseEntity.ok(responseBody(
                200,
                "获取生成状态成功",
                chatGenerationStateService.getGenerationForUser(generationId, userId).orElse(null)
        ));
    }

    @GetMapping("/active-generation")
    public ResponseEntity<?> getActiveGeneration(@RequestHeader("Authorization") String token) {
        String userId = extractValidatedUserId(token);
        if (userId == null) {
            return ResponseEntity.status(401).body(responseBody(401, "Invalid token", null));
        }

        return ResponseEntity.ok(responseBody(
                200,
                "获取活跃生成状态成功",
                chatGenerationStateService.getActiveGenerationForUser(userId).orElse(null)
        ));
    }

    private String extractValidatedUserId(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return null;
        }

        String jwtToken = authorization.replace("Bearer ", "");
        if (!jwtUtils.validateToken(jwtToken)) {
            return null;
        }
        return jwtUtils.extractUserIdFromToken(jwtToken);
    }

    private Map<String, Object> responseBody(int code, String message, Object data) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("code", code);
        response.put("message", message);
        response.put("data", data);
        return response;
    }
}
