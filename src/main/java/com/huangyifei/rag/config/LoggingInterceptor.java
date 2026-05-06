package com.huangyifei.rag.config;

import com.huangyifei.rag.utils.JwtUtils;
import com.huangyifei.rag.utils.LogUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.UUID;

@Component
public class LoggingInterceptor implements HandlerInterceptor {

    @Autowired
    private JwtUtils jwtUtils;

    private static final String START_TIME_ATTRIBUTE = "startTime";
    private static final String REQUEST_ID_ATTRIBUTE = "requestId";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        long startTime = System.currentTimeMillis();
        request.setAttribute(START_TIME_ATTRIBUTE, startTime);
        request.setAttribute(REQUEST_ID_ATTRIBUTE, UUID.randomUUID().toString().substring(0, 8));

        String userId = extractUserId(request);
        String path = request.getRequestURI();
        if (isApiRequest(path)) {
            LogUtils.logBusiness("REQUEST_START", userId, "Start request [%s] %s", request.getMethod(), path);
        }

        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        Long startTime = (Long) request.getAttribute(START_TIME_ATTRIBUTE);
        if (startTime == null) {
            return;
        }

        long duration = System.currentTimeMillis() - startTime;
        String userId = extractUserId(request);
        String path = request.getRequestURI();

        if (isApiRequest(path)) {
            LogUtils.logApiCall(request.getMethod(), path, userId, response.getStatus(), duration);
            if (ex != null) {
                LogUtils.logBusinessError("REQUEST_ERROR", userId,
                        "Request error [%s] %s", ex, request.getMethod(), path);
            }
            if (duration > 3000) {
                LogUtils.logPerformance("SLOW_REQUEST", duration,
                        String.format("[%s] %s [user:%s]", request.getMethod(), path, userId));
            }
        }
    }

    private String extractUserId(HttpServletRequest request) {
        try {
            String token = extractToken(request);
            if (token != null) {
                return jwtUtils.extractUserIdFromToken(token);
            }
        } catch (Exception ignored) {
        }
        return "anonymous";
    }

    private String extractToken(HttpServletRequest request) {
        String bearerToken = request.getHeader("Authorization");
        if (bearerToken != null && bearerToken.startsWith("Bearer ")) {
            return bearerToken.substring(7);
        }
        return null;
    }

    private boolean isApiRequest(String path) {
        return path.startsWith("/api/") || path.startsWith("/chat/");
    }
}
