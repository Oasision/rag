package com.huangyifei.rag.service;

import com.huangyifei.rag.config.RateLimitProperties;
import com.huangyifei.rag.exception.RateLimitExceededException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

@Service
public class RateLimitService {

    private final StringRedisTemplate stringRedisTemplate;
    private final RateLimitProperties properties;
    private final RateLimitConfigService rateLimitConfigService;
    private final UsageQuotaService usageQuotaService;

    public RateLimitService(
            StringRedisTemplate stringRedisTemplate,
            RateLimitProperties properties,
            RateLimitConfigService rateLimitConfigService,
            UsageQuotaService usageQuotaService
    ) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.properties = properties;
        this.rateLimitConfigService = rateLimitConfigService;
        this.usageQuotaService = usageQuotaService;
    }

    public void checkRegisterByIp(String ip) {
        checkSingleWindow("register:ip:" + ip, properties.getRegister().getMax(), properties.getRegister().getWindowSeconds(), "注册请求过于频繁");
    }

    public void checkLoginByIp(String ip) {
        checkSingleWindow("login:ip:" + ip, properties.getLogin().getMax(), properties.getLogin().getWindowSeconds(), "登录请求过于频繁");
    }

    public void checkChatByUser(String userId) {
        RateLimitConfigService.WindowLimitView limit = rateLimitConfigService.getCurrentSettings().chatMessage();
        checkSingleWindow("chat:user:" + userId, limit.max(), limit.windowSeconds(), "聊天请求过于频繁");
        usageQuotaService.recordChatRequest(userId);
    }

    public UsageQuotaService.TokenReservationBundle reserveLlmUsage(
            String userId,
            int estimatedPromptTokens,
            int maxCompletionTokens
    ) {
        RateLimitConfigService.TokenBudgetView limit = rateLimitConfigService.getCurrentSettings().llmGlobalToken();
        return usageQuotaService.reserveLlmTokensWithGlobalBudget(
                userId,
                estimatedPromptTokens,
                maxCompletionTokens,
                limit.minuteMax(),
                limit.minuteWindowSeconds(),
                limit.dayMax(),
                limit.dayWindowSeconds()
        );
    }

    public void checkEmbeddingQueryByUser(String userId) {
        RateLimitConfigService.DualWindowLimitView limit = rateLimitConfigService.getCurrentSettings().embeddingQueryRequest();
        checkSingleWindow("embedding:query:min:user:" + userId, limit.minuteMax(), limit.minuteWindowSeconds(), "Embedding鏌ヨ杩囦簬棰戠箒");
        checkSingleWindow("embedding:query:day:user:" + userId, limit.dayMax(), limit.dayWindowSeconds(), "Embedding鏌ヨ褰撴棩娆℃暟宸茶揪涓婇檺");
    }

    public UsageQuotaService.TokenReservationBundle reserveEmbeddingUploadUsage(String userId, java.util.List<String> texts) {
        RateLimitConfigService.TokenBudgetView limit = rateLimitConfigService.getCurrentSettings().embeddingUploadToken();
        return usageQuotaService.reserveEmbeddingTokensWithGlobalBudget(
                userId,
                texts,
                "embedding-upload",
                "Embedding涓婁紶鍏ㄧ綉鍒嗛挓Token棰勭畻宸茶揪涓婇檺",
                "Embedding涓婁紶鍏ㄧ綉褰撴棩Token棰勭畻宸茶揪涓婇檺",
                limit.minuteMax(),
                limit.minuteWindowSeconds(),
                limit.dayMax(),
                limit.dayWindowSeconds()
        );
    }

    public UsageQuotaService.TokenReservationBundle reserveEmbeddingQueryUsage(String userId, java.util.List<String> texts) {
        checkEmbeddingQueryByUser(userId);
        RateLimitConfigService.TokenBudgetView limit = rateLimitConfigService.getCurrentSettings().embeddingQueryGlobalToken();
        return usageQuotaService.reserveEmbeddingTokensWithGlobalBudget(
                userId,
                texts,
                "embedding-query",
                "Embedding鏌ヨ鍏ㄧ綉鍒嗛挓Token棰勭畻宸茶揪涓婇檺",
                "Embedding鏌ヨ鍏ㄧ綉褰撴棩Token棰勭畻宸茶揪涓婇檺",
                limit.minuteMax(),
                limit.minuteWindowSeconds(),
                limit.dayMax(),
                limit.dayWindowSeconds()
        );
    }

    private void checkSingleWindow(String key, long max, long windowSeconds, String message) {
        Long current = stringRedisTemplate.opsForValue().increment(key);
        if (current == null) {
            return;
        }

        if (current == 1) {
            stringRedisTemplate.expire(key, windowSeconds, TimeUnit.SECONDS);
        }

        if (current > max) {
            Long ttl = stringRedisTemplate.getExpire(key, TimeUnit.SECONDS);
            long retryAfterSeconds = ttl == null || ttl < 0 ? windowSeconds : ttl;
            throw new RateLimitExceededException(message, retryAfterSeconds);
        }
    }
}
