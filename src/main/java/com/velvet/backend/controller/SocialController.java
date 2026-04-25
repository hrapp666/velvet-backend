package com.velvet.backend.controller;

import com.velvet.backend.dto.AuthRequests.UserDto;
import com.velvet.backend.dto.MomentRequests.MomentDto;
import com.velvet.backend.entity.Favorite;
import com.velvet.backend.entity.Follow;
import com.velvet.backend.entity.User;
import com.velvet.backend.exception.AppException;
import com.velvet.backend.repository.FavoriteRepository;
import com.velvet.backend.repository.FollowRepository;
import com.velvet.backend.repository.MomentRepository;
import com.velvet.backend.repository.UserRepository;
import com.velvet.backend.service.MomentService;
import com.velvet.backend.service.SocialService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class SocialController {

    private final SocialService socialService;
    private final FavoriteRepository favoriteRepo;
    private final FollowRepository followRepo;
    private final MomentRepository momentRepo;
    private final UserRepository userRepo;
    private final MomentService momentService;

    @PostMapping("/moments/{id}/like")
    public Map<String, Object> toggleLike(@PathVariable Long id) {
        boolean liked = socialService.toggleLike(currentUserId(), id);
        return Map.of("liked", liked);
    }

    @PostMapping("/moments/{id}/favorite")
    public Map<String, Object> toggleFavorite(@PathVariable Long id) {
        boolean favorited = socialService.toggleFavorite(currentUserId(), id);
        return Map.of("favorited", favorited);
    }

    @PostMapping("/users/{id}/follow")
    public Map<String, Object> toggleFollow(@PathVariable Long id) {
        boolean followed = socialService.toggleFollow(currentUserId(), id);
        return Map.of("followed", followed);
    }

    /** 别名路由：/api/v1/social/follow/{id}，兼容旧客户端 */
    @PostMapping("/social/follow/{id}")
    public Map<String, Object> toggleFollowAlias(@PathVariable Long id) {
        return toggleFollow(id);
    }

    // ─── Issue 1: GET /social/following ─────────────────

    /** 当前用户的关注列表 */
    @GetMapping("/social/following")
    public Map<String, Object> myFollowing(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return getFollowingList(currentUserId(), page, size);
    }

    /** 指定用户的关注列表 */
    @GetMapping("/users/{userId}/following")
    public Map<String, Object> userFollowing(
            @PathVariable Long userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return getFollowingList(userId, page, size);
    }

    private Map<String, Object> getFollowingList(Long userId, int page, int size) {
        Page<Follow> p = followRepo.findByFollowerIdOrderByCreatedAtDesc(
                userId, PageRequest.of(page, Math.min(size, 50)));
        List<UserDto> users = p.getContent().stream()
                .map(f -> userRepo.findById(f.getFolloweeId()).orElse(null))
                .filter(u -> u != null)
                .map(this::toUserDto)
                .toList();
        return Map.of(
                "content", users,
                "totalElements", p.getTotalElements(),
                "totalPages", p.getTotalPages(),
                "number", p.getNumber(),
                "last", p.isLast()
        );
    }

    // ─── Issue 2: GET /social/followers ──────────────────

    /** 当前用户的粉丝列表 */
    @GetMapping("/social/followers")
    public Map<String, Object> myFollowers(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return getFollowersList(currentUserId(), page, size);
    }

    /** 指定用户的粉丝列表 */
    @GetMapping("/users/{userId}/followers")
    public Map<String, Object> userFollowers(
            @PathVariable Long userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return getFollowersList(userId, page, size);
    }

    private Map<String, Object> getFollowersList(Long userId, int page, int size) {
        Page<Follow> p = followRepo.findByFolloweeIdOrderByCreatedAtDesc(
                userId, PageRequest.of(page, Math.min(size, 50)));
        List<UserDto> users = p.getContent().stream()
                .map(f -> userRepo.findById(f.getFollowerId()).orElse(null))
                .filter(u -> u != null)
                .map(this::toUserDto)
                .toList();
        return Map.of(
                "content", users,
                "totalElements", p.getTotalElements(),
                "totalPages", p.getTotalPages(),
                "number", p.getNumber(),
                "last", p.isLast()
        );
    }

    // ─── Issue 3: GET /social/check/{userId} ────────────

    /** 检查当前用户是否关注了指定用户 */
    @GetMapping("/social/check/{userId}")
    public Map<String, Object> checkFollow(@PathVariable Long userId) {
        Long me = currentUserId();
        boolean followed = followRepo.existsByFollowerIdAndFolloweeId(me, userId);
        return Map.of("followed", followed);
    }

    // ─── Issue 4-5: Favorites alias routes ──────────────

    /** 我的收藏列表（别名：/moments/favorites） */
    @GetMapping("/moments/favorites")
    public Map<String, Object> myFavoritesAlias(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return myFavorites(page, size);
    }

    /** 别名路由：/users/me/favorites */
    @GetMapping("/users/me/favorites")
    public Map<String, Object> myFavoritesAlias2(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return myFavorites(page, size);
    }

    /** 别名路由：/favorites（无前缀） */
    @GetMapping("/favorites")
    public Map<String, Object> myFavoritesAlias3(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return myFavorites(page, size);
    }

    /** 检查某条动态是否被当前用户收藏 */
    @GetMapping("/moments/{id}/favorite")
    public Map<String, Object> checkFavorited(@PathVariable Long id) {
        boolean favorited = favoriteRepo.existsByUserIdAndMomentId(currentUserId(), id);
        return Map.of("favorited", favorited);
    }

    /** 我的收藏列表 */
    @GetMapping("/favorites/my")
    public Map<String, Object> myFavorites(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        Long uid = currentUserId();
        Page<Favorite> p = favoriteRepo.findByUserIdOrderByCreatedAtDesc(
                uid, PageRequest.of(page, Math.min(size, 50)));
        List<Long> momentIds = p.getContent().stream().map(Favorite::getMomentId).toList();
        List<MomentDto> moments = momentIds.stream()
                .map(id -> momentRepo.findById(id)
                        .map(m -> momentService.toDtoWithUser(m, uid))
                        .orElse(null))
                .filter(m -> m != null)
                .toList();
        return Map.of(
                "content", moments,
                "totalElements", p.getTotalElements(),
                "totalPages", p.getTotalPages(),
                "number", p.getNumber(),
                "last", p.isLast()
        );
    }

    private UserDto toUserDto(User u) {
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
