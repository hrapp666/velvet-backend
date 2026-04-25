package com.velvet.backend.controller;

import com.velvet.backend.dto.MomentRequests.*;
import com.velvet.backend.service.MomentService;
import com.velvet.backend.service.RecommendationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/moments")
@RequiredArgsConstructor
public class MomentController {

    private final MomentService momentService;
    private final RecommendationService recommendationService;

    /** 公开 feed — 不需要登录也能浏览 */
    @GetMapping("/public")
    public Page<MomentDto> listPublic(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return momentService.listPublic(currentUserIdOrNull(), page, size);
    }

    /** 某用户的动态 */
    @GetMapping("/public/user/{userId}")
    public Page<MomentDto> listByUser(
            @PathVariable Long userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return momentService.listByUser(userId, currentUserIdOrNull(), page, size);
    }

    /** 单条动态详情 */
    @GetMapping("/public/{id}")
    public MomentDto getById(@PathVariable Long id) {
        return momentService.getById(id, currentUserIdOrNull());
    }

    /**
     * 同城 / 附近动态（按距离升序）
     * <p>
     * 用法：GET /api/v1/moments/public/nearby?lat=31.23&lng=121.47&radiusKm=20&page=0&size=20
     * <p>
     * - lat / lng 必填（WGS84）
     * - radiusKm 默认 20km，自动 clamp 到 [1, 200]
     * - 返回 distanceMeters 字段（米）
     */
    @GetMapping("/public/nearby")
    public java.util.List<MomentDto> listNearby(
            @RequestParam double lat,
            @RequestParam double lng,
            @RequestParam(defaultValue = "20") double radiusKm,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return momentService.listNearby(lat, lng, radiusKm, currentUserIdOrNull(), page, size);
    }

    /**
     * 推荐动态（需登录）
     * <p>
     * v0 算法：基于用户最近 like / 收藏的 tag 集合，对最近 200 条 PUBLISHED 用 jaccard 排序。
     * 冷启动 fallback：返回最新 public moments。
     */
    @GetMapping("/recommended")
    public java.util.List<MomentDto> recommended(
            @RequestParam(defaultValue = "20") int limit
    ) {
        return recommendationService.recommendFor(currentUserId(), limit);
    }

    /** 发布动态（需登录） */
    @PostMapping
    public MomentDto create(@Valid @RequestBody CreateMomentRequest req) {
        return momentService.create(currentUserId(), req);
    }

    /** 删除动态（需登录） */
    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        momentService.delete(id, currentUserId());
    }

    private Long currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getPrincipal() == null) {
            throw new com.velvet.backend.exception.AppException("UNAUTHORIZED", "请先登录");
        }
        return (Long) auth.getPrincipal();
    }

    private Long currentUserIdOrNull() {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.getPrincipal() instanceof Long id) {
                return id;
            }
        } catch (Exception ignored) {}
        return null;
    }
}
