package com.huangyifei.rag.controller;

import com.huangyifei.rag.utils.JwtUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    @Autowired
    private JwtUtils jwtUtils;

    @PostMapping("/refreshToken")
    public ResponseEntity<?> refreshToken(@RequestBody RefreshTokenRequest request) {
        if (request.refreshToken() == null || request.refreshToken().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("code", 400, "message", "Refresh token cannot be empty"));
        }

        if (!jwtUtils.canRefreshExpiredToken(request.refreshToken()) && !jwtUtils.validateToken(request.refreshToken())) {
            return ResponseEntity.status(401).body(Map.of("code", 401, "message", "Invalid refresh token"));
        }

        String username = jwtUtils.extractUsernameFromToken(request.refreshToken());
        if (username == null || username.isEmpty()) {
            return ResponseEntity.status(401).body(Map.of("code", 401, "message", "Cannot extract username from refresh token"));
        }

        String newToken = jwtUtils.generateToken(username);
        String newRefreshToken = jwtUtils.generateRefreshToken(username);
        return ResponseEntity.ok(Map.of(
                "code", 200,
                "message", "Token refreshed successfully",
                "data", Map.of("token", newToken, "refreshToken", newRefreshToken)
        ));
    }

    @GetMapping("/error")
    public ResponseEntity<?> customBackendError(@RequestParam String code, @RequestParam String msg) {
        return ResponseEntity.status(Integer.parseInt(code)).body(Map.of("code", Integer.parseInt(code), "message", msg));
    }
}

record RefreshTokenRequest(String refreshToken) {
}
