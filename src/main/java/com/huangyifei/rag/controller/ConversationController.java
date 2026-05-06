package com.huangyifei.rag.controller;

import com.huangyifei.rag.exception.CustomException;
import com.huangyifei.rag.service.ConversationService;
import com.huangyifei.rag.utils.JwtUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/users/conversation")
public class ConversationController {

    @Autowired
    private JwtUtils jwtUtils;

    @Autowired
    private ConversationService conversationService;

    @GetMapping
    public ResponseEntity<?> getConversations(
            @RequestHeader("Authorization") String token,
            @RequestParam(required = false) String start_date,
            @RequestParam(required = false) String end_date,
            @RequestParam(required = false) String conversationId) {
        String username = jwtUtils.extractUsernameFromToken(token.replace("Bearer ", ""));
        if (username == null || username.isEmpty()) {
            throw new CustomException("Invalid token", HttpStatus.UNAUTHORIZED);
        }

        LocalDateTime startDateTime = parseStartDate(start_date);
        LocalDateTime endDateTime = parseEndDate(end_date);
        List<Map<String, Object>> messages = conversationService.toMessageHistory(
                conversationService.getConversations(username, conversationId, startDateTime, endDateTime),
                false
        );

        Map<String, Object> response = new HashMap<>();
        response.put("code", 200);
        response.put("message", "获取会话记录成功");
        response.put("data", messages);
        return ResponseEntity.ok(response);
    }

    private LocalDateTime parseStartDate(String dateTimeStr) {
        if (dateTimeStr == null || dateTimeStr.trim().isEmpty()) {
            return null;
        }

        try {
            return LocalDateTime.parse(dateTimeStr);
        } catch (DateTimeParseException e1) {
            if (dateTimeStr.length() == 16) {
                return LocalDateTime.parse(dateTimeStr + ":00");
            }
            if (dateTimeStr.length() == 13) {
                return LocalDateTime.parse(dateTimeStr + ":00:00");
            }
            if (dateTimeStr.length() == 10) {
                return LocalDate.parse(dateTimeStr).atStartOfDay();
            }
        }

        throw new CustomException("开始时间格式不正确: " + dateTimeStr, HttpStatus.BAD_REQUEST);
    }

    private LocalDateTime parseEndDate(String dateTimeStr) {
        if (dateTimeStr == null || dateTimeStr.trim().isEmpty()) {
            return null;
        }

        try {
            return LocalDateTime.parse(dateTimeStr);
        } catch (DateTimeParseException e1) {
            if (dateTimeStr.length() == 16) {
                return LocalDateTime.parse(dateTimeStr + ":59");
            }
            if (dateTimeStr.length() == 13) {
                return LocalDateTime.parse(dateTimeStr + ":59:59");
            }
            if (dateTimeStr.length() == 10) {
                return LocalDate.parse(dateTimeStr).plusDays(1).atStartOfDay().minusSeconds(1);
            }
        }

        throw new CustomException("结束时间格式不正确: " + dateTimeStr, HttpStatus.BAD_REQUEST);
    }
}
