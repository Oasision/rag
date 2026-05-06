package com.huangyifei.rag.controller;

import com.huangyifei.rag.exception.CustomException;
import com.huangyifei.rag.exception.RateLimitExceededException;
import com.huangyifei.rag.model.User;
import com.huangyifei.rag.model.UserTokenRecord;
import com.huangyifei.rag.repository.UserRepository;
import com.huangyifei.rag.service.RateLimitService;
import com.huangyifei.rag.service.UsageQuotaService;
import com.huangyifei.rag.service.UserService;
import com.huangyifei.rag.service.UserTokenService;
import com.huangyifei.rag.utils.JwtUtils;
import jakarta.servlet.http.HttpServletRequest;
import lombok.Builder;
import lombok.Data;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    @Autowired
    private UserService userService;

    @Autowired
    private JwtUtils jwtUtils;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RateLimitService rateLimitService;

    @Autowired
    private UsageQuotaService usageQuotaService;

    @Autowired
    private UserTokenService userTokenService;

    @PostMapping("/register")
    public ResponseEntity<?> register(@RequestBody UserRequest request, HttpServletRequest httpServletRequest) {
        try {
            rateLimitService.checkRegisterByIp(resolveClientIp(httpServletRequest));
            if (request.username() == null || request.username().isEmpty()
                    || request.password() == null || request.password().isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("code", 400, "message", "Username and password cannot be empty"));
            }
            userService.registerUser(request.username(), request.password(), request.inviteCode());
            return ResponseEntity.ok(Map.of("code", 200, "message", "User registered successfully"));
        } catch (RateLimitExceededException e) {
            return buildRateLimitResponse(e);
        } catch (CustomException e) {
            return ResponseEntity.status(e.getStatus()).body(Map.of("code", e.getStatus().value(), "message", e.getMessage()));
        }
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody UserRequest request, HttpServletRequest httpServletRequest) {
        try {
            rateLimitService.checkLoginByIp(resolveClientIp(httpServletRequest));
            if (request.username() == null || request.username().isEmpty()
                    || request.password() == null || request.password().isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("code", 400, "message", "Username and password cannot be empty"));
            }
            String username = userService.authenticateUser(request.username(), request.password());
            String token = jwtUtils.generateToken(username);
            String refreshToken = jwtUtils.generateRefreshToken(username);
            return ResponseEntity.ok(Map.of("code", 200, "message", "Login successful", "data", Map.of(
                    "token", token,
                    "refreshToken", refreshToken
            )));
        } catch (RateLimitExceededException e) {
            return buildRateLimitResponse(e);
        } catch (CustomException e) {
            return ResponseEntity.status(e.getStatus()).body(Map.of("code", e.getStatus().value(), "message", e.getMessage()));
        }
    }

    @GetMapping("/me")
    public ResponseEntity<?> getCurrentUser(@RequestHeader("Authorization") String token) {
        String username = jwtUtils.extractUsernameFromToken(token.replace("Bearer ", ""));
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new CustomException("User not found", HttpStatus.NOT_FOUND));
        user.setPassword(null);
        return ok("Get current user successful", user);
    }

    @GetMapping("/org-tags")
    public ResponseEntity<?> getUserOrgTags(@RequestHeader("Authorization") String token) {
        String username = jwtUtils.extractUsernameFromToken(token.replace("Bearer ", ""));
        return ok("Get user org tags successful", userService.getUserOrgTags(username));
    }

    @PutMapping("/primary-org")
    public ResponseEntity<?> setPrimaryOrg(@RequestHeader("Authorization") String token,
                                           @RequestBody PrimaryOrgRequest request) {
        String username = jwtUtils.extractUsernameFromToken(token.replace("Bearer ", ""));
        userService.setUserPrimaryOrg(username, request.primaryOrg());
        return ok("Primary org updated", null);
    }

    @GetMapping("/usage")
    public ResponseEntity<?> getCurrentUserUsage(@RequestHeader("Authorization") String token) {
        String userId = jwtUtils.extractUserIdFromToken(token.replace("Bearer ", ""));
        Map<String, Object> data = new HashMap<>();
        data.put("llmTokenBalance", userTokenService.getLlmTokenBalance(userId));
        data.put("embeddingTokenBalance", userTokenService.getEmbeddingTokenBalance(userId));
        data.put("llmTotalIncreaseTokens", userTokenService.getUserLlmTotalIncreaseTokens(userId));
        data.put("embeddingTotalIncreaseTokens", userTokenService.getUserEmbeddingTotalIncreaseTokens(userId));
        data.put("chatRequestCount", userTokenService.getUserTotalRequestCount("chat", userId));
        data.put("embeddingRequestCount", userTokenService.getUserTotalRequestCount("embedding", userId));
        return ok("Get usage successful", data);
    }

    @GetMapping("/upload-orgs")
    public ResponseEntity<?> getUploadOrgTags(@RequestHeader("Authorization") String token) {
        String username = jwtUtils.extractUsernameFromToken(token.replace("Bearer ", ""));
        return ok("Get upload org tags successful", userService.getUserOrgTags(username));
    }

    @PostMapping("/logout")
    public ResponseEntity<?> logout(@RequestHeader("Authorization") String token) {
        return ResponseEntity.ok(Map.of("code", 200, "message", "Logout successful"));
    }

    @PostMapping("/logout-all")
    public ResponseEntity<?> logoutAll(@RequestHeader("Authorization") String token) {
        return ResponseEntity.ok(Map.of("code", 200, "message", "Logout all successful"));
    }

    @GetMapping("/token-records")
    public ResponseEntity<?> getTokenRecords(
            @RequestHeader("Authorization") String token,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        String userId = jwtUtils.extractUserIdFromToken(token.replace("Bearer ", ""));
        Page<UserTokenRecord> recordPage = userTokenService.getUserTokenRecords(userId, page, size);
        Map<String, Object> responseData = new HashMap<>();
        responseData.put("content", recordPage.getContent());
        responseData.put("totalElements", recordPage.getTotalElements());
        responseData.put("totalPages", recordPage.getTotalPages());
        responseData.put("number", recordPage.getNumber());
        responseData.put("size", recordPage.getSize());
        responseData.put("first", recordPage.isFirst());
        responseData.put("last", recordPage.isLast());
        responseData.put("empty", recordPage.isEmpty());
        return ok("Get token records successful", responseData);
    }

    private ResponseEntity<?> ok(String message, Object data) {
        Map<String, Object> body = new HashMap<>();
        body.put("code", 200);
        body.put("message", message);
        if (data != null) {
            body.put("data", data);
        }
        return ResponseEntity.ok(body);
    }

    private ResponseEntity<?> buildRateLimitResponse(RateLimitExceededException e) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body(Map.of("code", 429, "message", e.getMessage()));
    }

    private String resolveClientIp(HttpServletRequest request) {
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            return forwardedFor.split(",")[0].trim();
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp;
        }
        return request.getRemoteAddr();
    }
}

record UserRequest(String username, String password, String inviteCode) {
}

record PrimaryOrgRequest(String primaryOrg) {
}

@Data
@Builder
class UserTokenRecordDTO {
    private Long id;
    private LocalDate recordDate;
    private String tokenType;
    private String changeType;
    private Long amount;
    private Long balanceBefore;
    private Long balanceAfter;
    private String reason;
    private String remark;
    private LocalDateTime createdAt;
    private Long requestCount;
}
