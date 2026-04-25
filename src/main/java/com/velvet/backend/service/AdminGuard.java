package com.velvet.backend.service;

import com.velvet.backend.exception.AppException;
import com.velvet.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * 简易管理员权限守卫
 *
 * <p>判定规则（满足任一即算 admin）：
 * <ol>
 *   <li>user.role == "ADMIN"</li>
 *   <li>user.username 在 velvet.admin.usernames 配置列表里</li>
 *   <li>user.id 在 velvet.admin.user-ids 配置列表里</li>
 * </ol>
 *
 * <p>典型用法：
 * <pre>
 *   adminGuard.ensureAdmin(userId);
 *   // ...
 * </pre>
 */
@Component
@RequiredArgsConstructor
public class AdminGuard {

    private final UserRepository userRepo;

    @Value("${velvet.admin.usernames:}")
    private String adminUsernamesCsv;

    @Value("${velvet.admin.user-ids:}")
    private String adminUserIdsCsv;

    public boolean isAdmin(Long userId) {
        if (userId == null) return false;
        return userRepo.findById(userId).map(u -> {
            if ("ADMIN".equalsIgnoreCase(u.getRole())) return true;
            Set<String> usernames = parseCsv(adminUsernamesCsv);
            if (usernames.contains(u.getUsername())) return true;
            Set<String> ids = parseCsv(adminUserIdsCsv);
            return ids.contains(String.valueOf(u.getId()));
        }).orElse(false);
    }

    public void ensureAdmin(Long userId) {
        if (!isAdmin(userId)) {
            throw new AppException("FORBIDDEN", "需要管理员权限");
        }
    }

    private static Set<String> parseCsv(String csv) {
        if (csv == null || csv.isBlank()) return new HashSet<>();
        return new HashSet<>(Arrays.asList(csv.split("\\s*,\\s*")));
    }
}
