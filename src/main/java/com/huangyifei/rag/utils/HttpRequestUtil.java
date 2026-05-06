package com.huangyifei.rag.utils;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;






@Slf4j
public class HttpRequestUtil {
    





    
    public static String readReqData(HttpServletRequest request) {
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new InputStreamReader(request.getInputStream(), StandardCharsets.UTF_8));
            StringBuilder stringBuilder = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                stringBuilder.append(line);
            }
            return stringBuilder.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (IOException e) {
                    log.error("璇锋眰鍙傛暟瑙ｆ瀽寮傚父! {}", request.getRequestURI(), e);
                }
            }
        }
    }


    public static String getClientIp() {
        
        HttpServletRequest request = ((ServletRequestAttributes) RequestContextHolder.currentRequestAttributes()).getRequest();
        if (request != null) {
            return resolveClientIp(request);
        }
        return "";
    }

    public static String resolveClientIp(HttpServletRequest request) {
        return firstUsableIp(
                request.getHeader("CF-Connecting-IP"),
                request.getHeader("True-Client-IP"),
                extractForwardedForIp(request.getHeader("X-Forwarded-For")),
                request.getHeader("X-Real-IP"),
                request.getHeader("Proxy-Client-IP"),
                request.getHeader("WL-Proxy-Client-IP"),
                request.getRemoteAddr()
        ).orElse("unknown");
    }

    private static Optional<String> firstUsableIp(String... candidates) {
        return Arrays.stream(candidates)
                .filter(HttpRequestUtil::isUsableIp)
                .findFirst();
    }

    private static boolean isUsableIp(String ip) {
        return ip != null && !ip.isBlank() && !"unknown".equalsIgnoreCase(ip);
    }

    private static String extractForwardedForIp(String xForwardedFor) {
        if (xForwardedFor == null || xForwardedFor.isBlank()) {
            return null;
        }
        return Arrays.stream(xForwardedFor.split(","))
                .map(String::trim)
                .filter(HttpRequestUtil::isUsableIp)
                .findFirst()
                .orElse(null);
    }
}