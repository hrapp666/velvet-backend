package com.velvet.backend.security;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.Refill;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 速率限制 (audit P0 安全 - 防暴力破解)
 * <p>
 * 策略:
 * - /api/v1/auth/login         5/min   (防登录暴破)
 * - /api/v1/auth/register      3/min   (防注册刷号)
 * - /api/v1/auth/*             10/min  (其他 auth 路径)
 * - /api/v1/                   60/min  (普通 API)
 * <p>
 * 实现:
 * - Bucket4j (内存) - 单机够用，分布式后续切 Redis backend
 * - Key = IP + path - 同 IP 不同 endpoint 独立限速
 * - 触发后返回 HTTP 429 + 简单 JSON
 * <p>
 * 9 库方法论:
 * - superpowers Phase 1: 调查现有 attack vector (auth = root cause)
 * - OpenSpace fallback: 内存 bucket 是 generic, Redis 是 specialized
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RateLimitFilter extends OncePerRequestFilter {

    /** key = clientIp + ":" + path · value = bucket */
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain chain
    ) throws ServletException, IOException {
        Bandwidth limit = limitFor(request.getRequestURI());
        if (limit == null) {
            chain.doFilter(request, response);
            return;
        }

        String key = clientIp(request) + ":" + request.getRequestURI();
        Bucket bucket = buckets.computeIfAbsent(key,
                k -> Bucket.builder().addLimit(limit).build());

        if (bucket.tryConsume(1)) {
            chain.doFilter(request, response);
        } else {
            // jakarta.servlet 没有 SC_TOO_MANY_REQUESTS 常量 (HTTP/1.1 引入晚) → 直接用 429
            response.setStatus(429);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(
                "{\"error\":\"rate_limited\",\"message\":\"操 作 过 于 频 繁 · 请 稍 后 再 试\"}"
            );
        }
    }

    /**
     * 决定 path 用哪种限速。null 表示不限。
     */
    private Bandwidth limitFor(String path) {
        if (path == null) return null;
        if (path.endsWith("/api/v1/auth/login")) {
            return Bandwidth.classic(5, Refill.intervally(5, Duration.ofMinutes(1)));
        }
        if (path.endsWith("/api/v1/auth/register")) {
            return Bandwidth.classic(3, Refill.intervally(3, Duration.ofMinutes(1)));
        }
        if (path.startsWith("/api/v1/auth/")) {
            return Bandwidth.classic(10, Refill.intervally(10, Duration.ofMinutes(1)));
        }
        if (path.startsWith("/api/v1/")) {
            return Bandwidth.classic(60, Refill.intervally(60, Duration.ofMinutes(1)));
        }
        return null;
    }

    /**
     * 拿 client IP - 优先从 X-Forwarded-For (cloudflared / nginx 反代场景)
     */
    private static String clientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            int comma = xff.indexOf(',');
            return (comma > 0 ? xff.substring(0, comma) : xff).trim();
        }
        String real = request.getHeader("X-Real-IP");
        if (real != null && !real.isBlank()) return real.trim();
        return request.getRemoteAddr();
    }
}
