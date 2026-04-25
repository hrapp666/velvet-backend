package com.velvet.backend.service;

import com.velvet.backend.security.JwtUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.concurrent.TimeUnit;

/**
 * Redis-backed refresh token 存储与黑名单。
 * key 设计：
 *   refresh:{sha256(token)} → userId   TTL = 7 天
 *   blacklist:{sha256(token)} → "1"    TTL = token 剩余有效期
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RefreshTokenService {

    private static final String REFRESH_PREFIX = "refresh:";
    private static final String BLACKLIST_PREFIX = "blacklist:";

    private final StringRedisTemplate redisTemplate;
    private final JwtUtils jwtUtils;

    /**
     * 颁发 refresh token：JWT 签发 + Redis 存储。
     */
    public String createRefreshToken(Long userId, String username) {
        String token = jwtUtils.generateRefreshToken(userId, username);
        String hash = sha256(token);
        redisTemplate.opsForValue().set(
                REFRESH_PREFIX + hash,
                String.valueOf(userId),
                jwtUtils.getRefreshExpirationMs(),
                TimeUnit.MILLISECONDS
        );
        return token;
    }

    /**
     * 验证 refresh token：JWT 签名有效 + Redis 存在 + 未黑名单。
     * 返回 userId，无效返回 null。
     */
    public Long validate(String token) {
        // 1. JWT 签名 + 过期校验
        Long userId = jwtUtils.parseUserId(token);
        if (userId == null) {
            return null;
        }

        String hash = sha256(token);

        // 2. 黑名单检查
        if (Boolean.TRUE.equals(redisTemplate.hasKey(BLACKLIST_PREFIX + hash))) {
            log.debug("refresh token 已被黑名单: {}", hash.substring(0, 8));
            return null;
        }

        // 3. Redis 存在检查
        String stored = redisTemplate.opsForValue().get(REFRESH_PREFIX + hash);
        if (stored == null) {
            log.debug("refresh token 不在 Redis 中: {}", hash.substring(0, 8));
            return null;
        }

        return userId;
    }

    /**
     * 从 refresh token 提取 username（用于颁发新 access token）。
     */
    public String parseUsername(String token) {
        return jwtUtils.parseUsername(token);
    }

    /**
     * 黑名单一个 refresh token（logout 时调用）。
     * 同时从 refresh: 存储中删除。
     */
    public void blacklist(String token) {
        String hash = sha256(token);
        long remainingMs = jwtUtils.getRemainingMs(token);

        // 从 refresh 存储删除
        redisTemplate.delete(REFRESH_PREFIX + hash);

        // 加入黑名单（TTL = 剩余有效期，过期自动清理）
        if (remainingMs > 0) {
            redisTemplate.opsForValue().set(
                    BLACKLIST_PREFIX + hash,
                    "1",
                    remainingMs,
                    TimeUnit.MILLISECONDS
            );
        }
        log.debug("refresh token 已黑名单: {}", hash.substring(0, 8));
    }

    private static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is always available in JDK
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
