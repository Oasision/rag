package com.huangyifei.rag.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@Service
public class TokenCacheService {

    private static final Logger logger = LoggerFactory.getLogger(TokenCacheService.class);

    private static final String TOKEN_PREFIX = "jwt:valid:";
    private static final String USER_TOKENS_PREFIX = "jwt:user:";
    private static final String REFRESH_PREFIX = "jwt:refresh:";
    private static final String BLACKLIST_PREFIX = "jwt:blacklist:";

    private final RedisTemplate<String, Object> redisTemplate;

    public TokenCacheService(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public void cacheToken(String tokenId, String userId, String username, long expireTimeMs) {
        try {
            Map<String, Object> tokenInfo = new HashMap<>();
            tokenInfo.put("userId", userId);
            tokenInfo.put("username", username);
            tokenInfo.put("expireTime", expireTimeMs);

            long ttlSeconds = Math.max((expireTimeMs - System.currentTimeMillis()) / 1000 + 300, 1);
            redisTemplate.opsForValue().set(TOKEN_PREFIX + tokenId, tokenInfo, ttlSeconds, TimeUnit.SECONDS);
            addTokenToUser(userId, tokenId, expireTimeMs);
        } catch (Exception e) {
            logger.error("Failed to cache token: {}", tokenId, e);
        }
    }

    public void cacheRefreshToken(String refreshTokenId, String userId, String tokenId, long expireTimeMs) {
        try {
            Map<String, Object> refreshInfo = new HashMap<>();
            refreshInfo.put("userId", userId);
            refreshInfo.put("tokenId", tokenId);
            refreshInfo.put("expireTime", expireTimeMs);

            long ttlSeconds = Math.max((expireTimeMs - System.currentTimeMillis()) / 1000, 1);
            redisTemplate.opsForValue().set(REFRESH_PREFIX + refreshTokenId, refreshInfo, ttlSeconds, TimeUnit.SECONDS);
        } catch (Exception e) {
            logger.error("Failed to cache refresh token: {}", refreshTokenId, e);
        }
    }

    public boolean isTokenValid(String tokenId) {
        try {
            return !isTokenBlacklisted(tokenId) && Boolean.TRUE.equals(redisTemplate.hasKey(TOKEN_PREFIX + tokenId));
        } catch (Exception e) {
            logger.error("Failed to check token validity: {}", tokenId, e);
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> getTokenInfo(String tokenId) {
        try {
            Object tokenInfo = redisTemplate.opsForValue().get(TOKEN_PREFIX + tokenId);
            return tokenInfo instanceof Map<?, ?> ? (Map<String, Object>) tokenInfo : null;
        } catch (Exception e) {
            logger.error("Failed to get token info: {}", tokenId, e);
            return null;
        }
    }

    public boolean isRefreshTokenValid(String refreshTokenId) {
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(REFRESH_PREFIX + refreshTokenId));
        } catch (Exception e) {
            logger.error("Failed to check refresh token validity: {}", refreshTokenId, e);
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> getRefreshTokenInfo(String refreshTokenId) {
        try {
            Object refreshInfo = redisTemplate.opsForValue().get(REFRESH_PREFIX + refreshTokenId);
            return refreshInfo instanceof Map<?, ?> ? (Map<String, Object>) refreshInfo : null;
        } catch (Exception e) {
            logger.error("Failed to get refresh token info: {}", refreshTokenId, e);
            return null;
        }
    }

    public void blacklistToken(String tokenId, long expireTimeMs) {
        try {
            long ttlSeconds = Math.max((expireTimeMs - System.currentTimeMillis()) / 1000, 1);
            redisTemplate.opsForValue().set(BLACKLIST_PREFIX + tokenId, System.currentTimeMillis(), ttlSeconds, TimeUnit.SECONDS);
        } catch (Exception e) {
            logger.error("Failed to blacklist token: {}", tokenId, e);
        }
    }

    public boolean isTokenBlacklisted(String tokenId) {
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(BLACKLIST_PREFIX + tokenId));
        } catch (Exception e) {
            logger.error("Failed to check token blacklist: {}", tokenId, e);
            return false;
        }
    }

    public void removeToken(String tokenId, String userId) {
        try {
            redisTemplate.delete(TOKEN_PREFIX + tokenId);
            if (userId != null && !userId.isBlank()) {
                removeTokenFromUser(userId, tokenId);
            }
        } catch (Exception e) {
            logger.error("Failed to remove token: {}", tokenId, e);
        }
    }

    public void removeAllUserTokens(String userId) {
        try {
            String userTokenKey = USER_TOKENS_PREFIX + userId + ":tokens";
            Set<Object> tokenIds = redisTemplate.opsForSet().members(userTokenKey);
            if (tokenIds != null) {
                tokenIds.forEach(tokenId -> removeToken(String.valueOf(tokenId), null));
            }
            redisTemplate.delete(userTokenKey);
        } catch (Exception e) {
            logger.error("Failed to remove all user tokens: {}", userId, e);
        }
    }

    public long getUserActiveTokenCount(String userId) {
        try {
            Long count = redisTemplate.opsForSet().size(USER_TOKENS_PREFIX + userId + ":tokens");
            return count == null ? 0 : count;
        } catch (Exception e) {
            logger.error("Failed to get user active token count: {}", userId, e);
            return 0;
        }
    }

    private void addTokenToUser(String userId, String tokenId, long expireTimeMs) {
        if (userId == null || userId.isBlank()) {
            return;
        }
        String key = USER_TOKENS_PREFIX + userId + ":tokens";
        redisTemplate.opsForSet().add(key, tokenId);
        long ttlSeconds = Math.max((expireTimeMs - System.currentTimeMillis()) / 1000 + 300, 1);
        redisTemplate.expire(key, Duration.ofSeconds(ttlSeconds));
    }

    private void removeTokenFromUser(String userId, String tokenId) {
        redisTemplate.opsForSet().remove(USER_TOKENS_PREFIX + userId + ":tokens", tokenId);
    }
}
