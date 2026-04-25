package com.velvet.backend.controller;

import com.velvet.backend.dto.AuthRequests.UserDto;
import com.velvet.backend.entity.User;
import com.velvet.backend.exception.AppException;
import com.velvet.backend.repository.CommentRepository;
import com.velvet.backend.repository.MomentRepository;
import com.velvet.backend.repository.OrderRepository;
import com.velvet.backend.repository.UserRepository;
import com.velvet.backend.service.AccountDeletionService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserController {

    private final UserRepository userRepository;
    private final MomentRepository momentRepository;
    private final OrderRepository orderRepository;
    private final CommentRepository commentRepository;
    private final AccountDeletionService accountDeletionService;

    /**
     * 当前登录用户的资料
     */
    @GetMapping("/me")
    public UserDto me() {
        User u = userRepository.findById(currentUserId())
                .orElseThrow(() -> new AppException("USER_NOT_FOUND", "用户不存在"));
        return toDto(u);
    }

    /**
     * 公开用户资料（看别人）
     */
    @GetMapping("/public/{id}")
    public UserDto publicProfile(@PathVariable Long id) {
        User u = userRepository.findById(id)
                .orElseThrow(() -> new AppException("USER_NOT_FOUND", "用户不存在"));
        return toDto(u);
    }

    /**
     * 更新当前用户资料 (nickname/bio/avatarUrl/coverUrl)
     */
    @PutMapping("/me")
    public UserDto updateMe(@RequestBody Map<String, Object> body) {
        User u = userRepository.findById(currentUserId())
                .orElseThrow(() -> new AppException("USER_NOT_FOUND", "用户不存在"));

        if (body.containsKey("nickname")) {
            String nickname = (String) body.get("nickname");
            if (nickname != null && !nickname.isBlank()) {
                if (nickname.length() > 32) {
                    throw new AppException("INVALID_REQUEST", "昵称太长");
                }
                u.setNickname(nickname.trim());
            }
        }
        if (body.containsKey("bio")) {
            String bio = (String) body.get("bio");
            if (bio != null && bio.length() > 500) {
                throw new AppException("INVALID_REQUEST", "简介太长");
            }
            u.setBio(bio);
        }
        if (body.containsKey("avatarUrl")) {
            u.setAvatarUrl((String) body.get("avatarUrl"));
        }
        if (body.containsKey("coverUrl")) {
            u.setCoverUrl((String) body.get("coverUrl"));
        }

        return toDto(userRepository.save(u));
    }

    /**
     * 用户数据导出（GDPR 式便携性）— 返回当前用户的所有数据 JSON
     */
    @GetMapping("/me/export")
    public ResponseEntity<Map<String, Object>> exportMe() {
        Long uid = currentUserId();
        User u = userRepository.findById(uid)
                .orElseThrow(() -> new AppException("USER_NOT_FOUND", "用户不存在"));

        Map<String, Object> data = new LinkedHashMap<>();
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("id", u.getId());
        profile.put("username", u.getUsername());
        profile.put("nickname", u.getNickname());
        profile.put("bio", u.getBio());
        profile.put("avatarUrl", u.getAvatarUrl());
        profile.put("role", u.getRole());
        profile.put("merchantStatus", u.getMerchantStatus());
        profile.put("createdAt", u.getCreatedAt());
        data.put("profile", profile);

        // 最近 500 条动态（避免太大）
        var moments = momentRepository.findByUserIdAndStatusOrderByCreatedAtDesc(
                uid, "PUBLISHED", PageRequest.of(0, 500));
        data.put("moments", moments.getContent());
        data.put("momentsTotal", moments.getTotalElements());

        // 最近 500 单
        var buyerOrders = orderRepository.findByBuyerIdOrderByCreatedAtDesc(
                uid, PageRequest.of(0, 500));
        var sellerOrders = orderRepository.findBySellerIdOrderByCreatedAtDesc(
                uid, PageRequest.of(0, 500));
        data.put("ordersAsBuyer", buyerOrders.getContent());
        data.put("ordersAsSeller", sellerOrders.getContent());

        data.put("exportedAt", LocalDateTime.now());

        // 作为下载返回
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"velvet-export-" + u.getUsername() + ".json\"");
        return ResponseEntity.ok().headers(headers).body(data);
    }

    /**
     * 账号注销 · Apple 5.1.1(v) 合规
     * body: { "confirmation": "DELETE_MY_ACCOUNT" }
     */
    @DeleteMapping("/me")
    public Map<String, String> deleteMe(@RequestBody Map<String, String> body) {
        String confirmation = body == null ? null : body.get("confirmation");
        accountDeletionService.deleteAccount(currentUserId(), confirmation);
        return Map.of("status", "DELETED");
    }

    private UserDto toDto(User u) {
        return new UserDto(
                u.getId(),
                u.getUsername(),
                u.getNickname(),
                u.getAvatarUrl(),
                u.getBio(),
                u.getMomentsCount(),
                u.getFollowersCount(),
                u.getFollowingCount(),
                u.getRole(),
                u.getMerchantStatus() != null ? u.getMerchantStatus() : "NONE"
        );
    }

    private Long currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof Long id)) {
            throw new AppException("UNAUTHORIZED", "请先登录");
        }
        return id;
    }
}
