package com.velvet.backend.controller;

import com.velvet.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class HealthController {

    private final UserRepository userRepository;
    private final StringRedisTemplate redisTemplate;

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "ok");
        response.put("service", "velvet-backend");
        response.put("version", "0.1.0");
        response.put("tagline", "Touch what was touched.");
        return response;
    }

    /** 详细依赖检查（DB / Redis / 应用就绪） */
    @GetMapping("/health/deep")
    public Map<String, Object> deepHealth() {
        Map<String, Object> deps = new HashMap<>();

        // MySQL
        try {
            long userCount = userRepository.count();
            deps.put("mysql", Map.of("status", "up", "userCount", userCount));
        } catch (Exception e) {
            log.warn("mysql health check failed", e);
            deps.put("mysql", Map.of("status", "down", "error", e.getClass().getSimpleName()));
        }

        // Redis
        try {
            String pong = redisTemplate.getConnectionFactory()
                    .getConnection().ping();
            deps.put("redis", Map.of("status", pong != null ? "up" : "down"));
        } catch (Exception e) {
            log.warn("redis health check failed", e);
            deps.put("redis", Map.of("status", "down", "error", e.getClass().getSimpleName()));
        }

        boolean allUp = deps.values().stream()
                .allMatch(m -> "up".equals(((Map<?, ?>) m).get("status")));

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", allUp ? "ok" : "degraded");
        response.put("service", "velvet-backend");
        response.put("version", "0.1.0");
        response.put("dependencies", deps);
        return response;
    }
}
