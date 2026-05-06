package com.huangyifei.rag.utils;

import com.huangyifei.rag.model.User;
import com.huangyifei.rag.repository.UserRepository;
import com.huangyifei.rag.service.TokenCacheService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Component
public class JwtUtils {

    private static final Logger logger = LoggerFactory.getLogger(JwtUtils.class);
    private static final long EXPIRATION_TIME = 3_600_000L;
    private static final long REFRESH_TOKEN_EXPIRATION_TIME = 604_800_000L;
    private static final long REFRESH_THRESHOLD = 300_000L;
    private static final long REFRESH_WINDOW = 604_800_000L;

    @Value("${jwt.secret-key}")
    private String secretKeyBase64;

    private final UserRepository userRepository;
    private final TokenCacheService tokenCacheService;

    public JwtUtils(UserRepository userRepository, TokenCacheService tokenCacheService) {
        this.userRepository = userRepository;
        this.tokenCacheService = tokenCacheService;
    }

    public String generateToken(String username) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("User not found"));

        String tokenId = generateTokenId();
        long expireTime = System.currentTimeMillis() + EXPIRATION_TIME;

        Map<String, Object> claims = new HashMap<>();
        claims.put("tokenId", tokenId);
        claims.put("role", user.getRole().name());
        claims.put("userId", user.getId().toString());
        if (user.getOrgTags() != null && !user.getOrgTags().isBlank()) {
            claims.put("orgTags", user.getOrgTags());
        }
        if (user.getPrimaryOrg() != null && !user.getPrimaryOrg().isBlank()) {
            claims.put("primaryOrg", user.getPrimaryOrg());
        }

        String token = Jwts.builder()
                .setClaims(claims)
                .setSubject(username)
                .setExpiration(new Date(expireTime))
                .signWith(getSigningKey(), SignatureAlgorithm.HS256)
                .compact();
        tokenCacheService.cacheToken(tokenId, user.getId().toString(), username, expireTime);
        return token;
    }

    public boolean validateToken(String token) {
        try {
            String tokenId = extractTokenIdFromToken(token);
            if (tokenId == null || !tokenCacheService.isTokenValid(tokenId)) {
                return false;
            }
            extractClaims(token);
            return true;
        } catch (Exception e) {
            logger.debug("Token validation failed: {}", e.getMessage());
            return false;
        }
    }

    public String extractUsernameFromToken(String token) {
        Claims claims = extractClaimsIgnoreExpiration(token);
        return claims == null ? null : claims.getSubject();
    }

    public String extractUserIdFromToken(String token) {
        Claims claims = extractClaimsIgnoreExpiration(token);
        return claims == null ? null : claims.get("userId", String.class);
    }

    public String extractRoleFromToken(String token) {
        Claims claims = extractClaimsIgnoreExpiration(token);
        return claims == null ? null : claims.get("role", String.class);
    }

    public String extractOrgTagsFromToken(String token) {
        Claims claims = extractClaimsIgnoreExpiration(token);
        return claims == null ? null : claims.get("orgTags", String.class);
    }

    public String extractPrimaryOrgFromToken(String token) {
        Claims claims = extractClaimsIgnoreExpiration(token);
        return claims == null ? null : claims.get("primaryOrg", String.class);
    }

    public boolean shouldRefreshToken(String token) {
        Claims claims = extractClaims(token);
        if (claims == null || claims.getExpiration() == null) {
            return false;
        }
        long remainingTime = claims.getExpiration().getTime() - System.currentTimeMillis();
        return remainingTime > 0 && remainingTime < REFRESH_THRESHOLD;
    }

    public boolean canRefreshExpiredToken(String token) {
        Claims claims = extractClaimsIgnoreExpiration(token);
        if (claims == null || claims.getExpiration() == null) {
            return false;
        }
        long expiredTime = System.currentTimeMillis() - claims.getExpiration().getTime();
        return expiredTime > 0 && expiredTime < REFRESH_WINDOW;
    }

    public String refreshToken(String oldToken) {
        Claims claims = extractClaimsIgnoreExpiration(oldToken);
        if (claims == null || claims.getSubject() == null || claims.getSubject().isBlank()) {
            return null;
        }
        return generateToken(claims.getSubject());
    }

    public String generateRefreshToken(String username) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("User not found"));

        String refreshTokenId = generateTokenId();
        long expireTime = System.currentTimeMillis() + REFRESH_TOKEN_EXPIRATION_TIME;

        Map<String, Object> claims = new HashMap<>();
        claims.put("refreshTokenId", refreshTokenId);
        claims.put("userId", user.getId().toString());
        claims.put("type", "refresh");

        String refreshToken = Jwts.builder()
                .setClaims(claims)
                .setSubject(username)
                .setExpiration(new Date(expireTime))
                .signWith(getSigningKey(), SignatureAlgorithm.HS256)
                .compact();
        tokenCacheService.cacheRefreshToken(refreshTokenId, user.getId().toString(), null, expireTime);
        return refreshToken;
    }

    public boolean validateRefreshToken(String refreshToken) {
        try {
            String refreshTokenId = extractRefreshTokenIdFromToken(refreshToken);
            if (refreshTokenId == null || !tokenCacheService.isRefreshTokenValid(refreshTokenId)) {
                return false;
            }
            Claims claims = extractClaims(refreshToken);
            return claims != null && "refresh".equals(claims.get("type", String.class));
        } catch (Exception e) {
            logger.debug("Refresh token validation failed: {}", e.getMessage());
            return false;
        }
    }

    public String extractRefreshTokenIdFromToken(String refreshToken) {
        Claims claims = extractClaimsIgnoreExpiration(refreshToken);
        return claims == null ? null : claims.get("refreshTokenId", String.class);
    }

    public String extractTokenIdFromToken(String token) {
        Claims claims = extractClaimsIgnoreExpiration(token);
        return claims == null ? null : claims.get("tokenId", String.class);
    }

    public void invalidateToken(String token) {
        String tokenId = extractTokenIdFromToken(token);
        Claims claims = extractClaimsIgnoreExpiration(token);
        if (tokenId != null && claims != null) {
            tokenCacheService.removeToken(tokenId, claims.get("userId", String.class));
        }
    }

    public void invalidateAllUserTokens(String userId) {
        tokenCacheService.removeAllUserTokens(userId);
    }

    private Claims extractClaimsIgnoreExpiration(String token) {
        try {
            return Jwts.parserBuilder()
                    .setSigningKey(getSigningKey())
                    .build()
                    .parseClaimsJws(token)
                    .getBody();
        } catch (ExpiredJwtException e) {
            return e.getClaims();
        } catch (Exception e) {
            return null;
        }
    }

    private Claims extractClaims(String token) {
        return Jwts.parserBuilder()
                .setSigningKey(getSigningKey())
                .build()
                .parseClaimsJws(token)
                .getBody();
    }

    private SecretKey getSigningKey() {
        byte[] keyBytes = Base64.getDecoder().decode(secretKeyBase64);
        return Keys.hmacShaKeyFor(keyBytes);
    }

    private String generateTokenId() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
