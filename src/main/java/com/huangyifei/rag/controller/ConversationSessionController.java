package com.huangyifei.rag.controller;

import com.huangyifei.rag.exception.CustomException;
import com.huangyifei.rag.service.ConversationService;
import com.huangyifei.rag.utils.JwtUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/users/conversations")
public class ConversationSessionController {

    private final JwtUtils jwtUtils;
    private final ConversationService conversationService;

    public ConversationSessionController(JwtUtils jwtUtils, ConversationService conversationService) {
        this.jwtUtils = jwtUtils;
        this.conversationService = conversationService;
    }

    @GetMapping
    public ResponseEntity<?> listSessions(@RequestHeader("Authorization") String token) {
        Long userId = extractUserId(token);
        return ResponseEntity.ok(response(200, "OK", conversationService.getConversationSessions(userId)));
    }

    @PostMapping
    public ResponseEntity<?> createSession(@RequestHeader("Authorization") String token) {
        Long userId = extractUserId(token);
        return ResponseEntity.ok(response(200, "OK", conversationService.createConversationSession(userId)));
    }

    @PutMapping("/{conversationId}/switch")
    public ResponseEntity<?> switchSession(
            @RequestHeader("Authorization") String token,
            @PathVariable String conversationId) {
        Long userId = extractUserId(token);
        conversationService.switchCurrentConversation(userId, conversationId);
        return ResponseEntity.ok(response(200, "OK", null));
    }

    @PutMapping("/{conversationId}/archive")
    public ResponseEntity<?> archiveSession(
            @RequestHeader("Authorization") String token,
            @PathVariable String conversationId) {
        Long userId = extractUserId(token);
        conversationService.archiveConversationSession(userId, conversationId);
        return ResponseEntity.ok(response(200, "OK", null));
    }

    @PutMapping("/{conversationId}/unarchive")
    public ResponseEntity<?> unarchiveSession(
            @RequestHeader("Authorization") String token,
            @PathVariable String conversationId) {
        Long userId = extractUserId(token);
        conversationService.unarchiveConversationSession(userId, conversationId);
        return ResponseEntity.ok(response(200, "OK", null));
    }

    private Long extractUserId(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw new CustomException("Invalid token", HttpStatus.UNAUTHORIZED);
        }

        String jwtToken = authorization.replace("Bearer ", "");
        if (!jwtUtils.validateToken(jwtToken)) {
            throw new CustomException("Invalid token", HttpStatus.UNAUTHORIZED);
        }

        String userId = jwtUtils.extractUserIdFromToken(jwtToken);
        if (userId == null || userId.isBlank()) {
            throw new CustomException("Invalid token", HttpStatus.UNAUTHORIZED);
        }
        return Long.parseLong(userId);
    }

    private Map<String, Object> response(int code, String message, Object data) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("code", code);
        response.put("message", message);
        response.put("data", data);
        return response;
    }
}
