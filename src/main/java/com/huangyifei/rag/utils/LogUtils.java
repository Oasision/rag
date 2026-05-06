package com.huangyifei.rag.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

public class LogUtils {

    private static final Logger BUSINESS_LOGGER = LoggerFactory.getLogger("business");
    private static final Logger PERFORMANCE_LOGGER = LoggerFactory.getLogger("performance");

    public static final String USER_ID = "userId";
    public static final String REQUEST_ID = "requestId";
    public static final String SESSION_ID = "sessionId";
    public static final String OPERATION = "operation";

    private LogUtils() {
    }

    public static void logBusiness(String operation, String userId, String message, Object... args) {
        withContext(operation, userId, () -> BUSINESS_LOGGER.info("[{}] [user:{}] {}", operation, userId, formatMessage(message, args)));
    }

    public static void logBusinessError(String operation, String userId, String message, Throwable throwable, Object... args) {
        withContext(operation, userId, () -> BUSINESS_LOGGER.error("[{}] [user:{}] {}", operation, userId, formatMessage(message, args), throwable));
    }

    public static void logPerformance(String operation, long duration, String details) {
        withContext(operation, null, () -> PERFORMANCE_LOGGER.info("[performance] [{}] {}ms {}", operation, duration, details));
    }

    public static void logUserOperation(String userId, String operation, String resource, String result) {
        logBusiness(operation, userId, "resource=%s result=%s", resource, result);
    }

    public static void logApiCall(String method, String path, String userId, int statusCode, long duration) {
        logBusiness("API_CALL", userId, "%s %s status=%s duration=%sms", method, path, statusCode, duration);
    }

    public static void logFileOperation(String userId, String operation, String fileName, String fileMd5, String result) {
        logBusiness("FILE_" + operation, userId, "file=%s md5=%s result=%s", fileName, fileMd5, result);
    }

    public static void logChat(String userId, String sessionId, String messageType, int messageLength) {
        setRequestContext(null, userId, sessionId);
        try {
            BUSINESS_LOGGER.info("[chat] [user:{}] [session:{}] [type:{}] [length:{}]", userId, sessionId, messageType, messageLength);
        } finally {
            MDC.clear();
        }
    }

    public static void logSystemStart(String component, String status, String details) {
        BUSINESS_LOGGER.info("[system-start] [{}] [{}] {}", component, status, details);
    }

    public static void logSystemError(String component, String error, Throwable throwable) {
        BUSINESS_LOGGER.error("[system-error] [{}] {}", component, error, throwable);
    }

    public static void setRequestContext(String requestId, String userId, String sessionId) {
        if (requestId != null) {
            MDC.put(REQUEST_ID, requestId);
        }
        if (userId != null) {
            MDC.put(USER_ID, userId);
        }
        if (sessionId != null) {
            MDC.put(SESSION_ID, sessionId);
        }
    }

    public static void clearRequestContext() {
        MDC.clear();
    }

    public static PerformanceMonitor startPerformanceMonitor(String operation) {
        return new PerformanceMonitor(operation);
    }

    private static void withContext(String operation, String userId, Runnable runnable) {
        try {
            MDC.put(OPERATION, operation);
            if (userId != null) {
                MDC.put(USER_ID, userId);
            }
            runnable.run();
        } finally {
            MDC.clear();
        }
    }

    private static String formatMessage(String message, Object... args) {
        if (args == null || args.length == 0) {
            return message;
        }
        try {
            return String.format(message, args);
        } catch (Exception e) {
            return message;
        }
    }

    public static class PerformanceMonitor {
        private final String operation;
        private final long startTime;

        public PerformanceMonitor(String operation) {
            this.operation = operation;
            this.startTime = System.currentTimeMillis();
        }

        public void end() {
            end("");
        }

        public void end(String details) {
            logPerformance(operation, System.currentTimeMillis() - startTime, details);
        }
    }
}
