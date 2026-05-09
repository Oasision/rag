package com.huangyifei.rag.controller;

import com.huangyifei.rag.exception.CustomException;
import com.huangyifei.rag.service.KnowledgeSearchToolService;
import com.huangyifei.rag.utils.JwtUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/agent/tools")
public class AgentToolController {

    private final JwtUtils jwtUtils;
    private final KnowledgeSearchToolService knowledgeSearchToolService;

    public AgentToolController(JwtUtils jwtUtils, KnowledgeSearchToolService knowledgeSearchToolService) {
        this.jwtUtils = jwtUtils;
        this.knowledgeSearchToolService = knowledgeSearchToolService;
    }

    @PostMapping("/knowledge-search")
    public ResponseEntity<?> knowledgeSearch(
            @RequestHeader("Authorization") String token,
            @RequestBody KnowledgeSearchPayload payload) {
        String userId = extractUserId(token);
        KnowledgeSearchToolService.KnowledgeSearchResponse result = knowledgeSearchToolService.execute(
                new KnowledgeSearchToolService.KnowledgeSearchRequest(payload.query(), userId, payload.topK())
        );
        return ResponseEntity.ok(response(200, "OK", result));
    }

    private String extractUserId(String authorization) {
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
        return userId;
    }

    private Map<String, Object> response(int code, String message, Object data) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("message", message);
        body.put("data", data);
        return body;
    }

    public record KnowledgeSearchPayload(
            String query,
            Integer topK
    ) {
    }
}
