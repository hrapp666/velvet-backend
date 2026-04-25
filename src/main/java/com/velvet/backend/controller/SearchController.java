package com.velvet.backend.controller;

import com.velvet.backend.dto.AuthRequests.UserDto;
import com.velvet.backend.dto.MomentRequests.MomentDto;
import com.velvet.backend.entity.Moment;
import com.velvet.backend.entity.User;
import com.velvet.backend.repository.MomentRepository;
import com.velvet.backend.repository.UserRepository;
import com.velvet.backend.service.MomentService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/search")
@RequiredArgsConstructor
public class SearchController {

    private final MomentRepository momentRepository;
    private final UserRepository userRepository;
    private final MomentService momentService;

    /**
     * 综合搜索 — 同时搜 moments + users，公开
     * 客户端调用 /api/v1/search/public?q=xxx
     */
    @GetMapping("/public")
    public Map<String, Object> searchAll(
            @RequestParam("q") String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        if (q == null || q.isBlank()) {
            return Map.of("moments", Page.empty(), "users", Page.empty());
        }
        var pageable = PageRequest.of(page, Math.min(size, 50));
        String trimmed = q.trim();

        Page<MomentDto> moments = momentRepository.searchByQuery(trimmed, pageable)
                .map(m -> momentService.toDtoWithUser(m, null));

        Page<UserDto> users = userRepository.searchByQuery(trimmed, pageable)
                .map(u -> new UserDto(
                        u.getId(), u.getUsername(), u.getNickname(), u.getAvatarUrl(),
                        u.getBio(), u.getMomentsCount(), u.getFollowersCount(),
                        u.getFollowingCount(), u.getRole(),
                        u.getMerchantStatus() != null ? u.getMerchantStatus() : "NONE"
                ));

        return Map.of("moments", moments, "users", users);
    }

    /**
     * 搜索 moments — 公开，无需登录
     */
    @GetMapping("/public/moments")
    public Page<MomentDto> searchMoments(
            @RequestParam("q") String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        if (q == null || q.isBlank()) {
            return Page.empty();
        }
        var pageable = PageRequest.of(page, Math.min(size, 50));
        Page<Moment> result = momentRepository.searchByQuery(q.trim(), pageable);
        return result.map(m -> momentService.toDtoWithUser(m, null));
    }

    /**
     * 搜索 users — 公开
     */
    @GetMapping("/public/users")
    public Page<UserDto> searchUsers(
            @RequestParam("q") String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        if (q == null || q.isBlank()) {
            return Page.empty();
        }
        var pageable = PageRequest.of(page, Math.min(size, 50));
        Page<User> result = userRepository.searchByQuery(q.trim(), pageable);
        return result.map(u -> new UserDto(
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
        ));
    }
}
