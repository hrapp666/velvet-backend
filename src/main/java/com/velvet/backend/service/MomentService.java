package com.velvet.backend.service;

import com.velvet.backend.dto.MomentRequests.*;
import com.velvet.backend.entity.Moment;
import com.velvet.backend.entity.User;
import com.velvet.backend.exception.AppException;
import com.velvet.backend.repository.FavoriteRepository;
import com.velvet.backend.repository.LikeRepository;
import com.velvet.backend.repository.MomentRepository;
import com.velvet.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class MomentService {

    private final MomentRepository momentRepo;
    private final UserRepository userRepo;
    private final LikeRepository likeRepo;
    private final FavoriteRepository favoriteRepo;
    private final com.velvet.backend.security.HtmlSanitizer htmlSanitizer;
    private final ContentModerationService contentModerationService;
    private final BlockService blockService;

    @Transactional
    public MomentDto create(Long userId, CreateMomentRequest req) {
        User user = userRepo.findById(userId)
                .orElseThrow(() -> new AppException("USER_NOT_FOUND", "用户不存在"));

        // v26 苹果合规：本版本无交易功能 · 强制忽略客户端提交的 hasItem/itemPriceCents
        // 即使老客户端意外发送，也以 false/null 入库，确保数据干净。

        // 内容审核 — DB 写入前拦截违规文本（v0 本地词表；v1 云 API 接入后替换）
        contentModerationService.moderateText(req.title(), "moment_title_user_" + userId);
        contentModerationService.moderateText(req.content(), "moment_content_user_" + userId);
        if (req.tags() != null && !req.tags().isEmpty()) {
            contentModerationService.moderateText(
                    String.join(" ", req.tags()), "moment_tags_user_" + userId);
        }

        Moment moment = Moment.builder()
                .userId(userId)
                // XSS 防护: title 纯文本 strip / content 富文本保留 basic 标签
                .title(htmlSanitizer.sanitizePlain(req.title()))
                .content(htmlSanitizer.sanitizeRich(req.content()))
                .coverUrl(req.coverUrl())
                .mediaUrls(req.mediaUrls() != null ? req.mediaUrls() : List.of())
                .hasItem(false)
                .itemPriceCents(null)
                .itemAttributes(req.itemAttributes() != null ? req.itemAttributes() : new HashMap<>())
                .tags(req.tags() != null ? req.tags().toArray(new String[0]) : new String[0])
                .location(req.location())
                .latitude(sanitizeLat(req.latitude()))
                .longitude(sanitizeLng(req.longitude()))
                .visibility(req.visibility() != null ? req.visibility() : "PUBLIC")
                // v26 苹果合规：先审后发。文本审核已通过（上面 moderateText），
                // 但图片/视频内容仍需后台人工/AI 复核才能进入 feed。
                .status("PENDING_REVIEW")
                .build();

        Moment saved = momentRepo.save(moment);

        // momentsCount 仅统计已发布；PENDING 不计入，避免计数虚高
        // 审核通过时再 +1，见 ModerationService.approveMoment()

        return toDto(saved, user, false, false, null);
    }

    @Transactional(readOnly = true)
    public Page<MomentDto> listPublic(Long viewerId, int page, int size) {
        Pageable pageable = PageRequest.of(page, Math.min(size, 50));
        Page<Moment> moments = momentRepo.findByStatusOrderByCreatedAtDesc("PUBLISHED", pageable);
        // 拉黑过滤 — 双向：隐藏我拉黑的 + 拉黑我的用户发的动态
        // 注：过滤后单页可能少于 size;前端靠 Page.totalElements 判定到底,可接受
        Set<Long> blockedSet = viewerId == null
                ? Set.of()
                : new HashSet<>(blockService.mutualBlockedIds(viewerId));
        Map<Long, User> userMap = batchFetchUsers(moments.getContent());
        List<Moment> visible = moments.getContent().stream()
                .filter(m -> !blockedSet.contains(m.getUserId()))
                .toList();
        Set<Long> likedSet = batchFetchLikedIds(viewerId, visible);
        Set<Long> favoritedSet = batchFetchFavoritedIds(viewerId, visible);
        List<MomentDto> dtos = visible.stream()
                .map(m -> toDto(m, userMap.get(m.getUserId()),
                        likedSet.contains(m.getId()),
                        favoritedSet.contains(m.getId()),
                        null))
                .toList();
        return new org.springframework.data.domain.PageImpl<>(dtos, pageable, moments.getTotalElements());
    }

    @Transactional(readOnly = true)
    public Page<MomentDto> listByUser(Long userId, Long viewerId, int page, int size) {
        // 拉黑过滤 — 任一方向拉黑即返回空页
        if (viewerId != null && blockService.existsBetween(viewerId, userId)) {
            return Page.empty(PageRequest.of(page, Math.min(size, 50)));
        }
        Pageable pageable = PageRequest.of(page, Math.min(size, 50));
        // 作者自己看自己：能看到 PENDING_REVIEW / PUBLISHED / REJECTED（用于显示审核态徽标）
        // 别人看：只看 PUBLISHED
        boolean isSelf = viewerId != null && viewerId.equals(userId);
        Page<Moment> moments = isSelf
                ? momentRepo.findByUserIdExcludingDeleted(userId, pageable)
                : momentRepo.findByUserIdAndStatusOrderByCreatedAtDesc(
                        userId, "PUBLISHED", pageable
                );
        // N+1 fix: 同 userId，只 fetch 一次
        User user = userRepo.findById(userId).orElse(null);
        Set<Long> likedSet = batchFetchLikedIds(viewerId, moments.getContent());
        Set<Long> favoritedSet = batchFetchFavoritedIds(viewerId, moments.getContent());
        return moments.map(m -> toDto(m, user,
                likedSet.contains(m.getId()),
                favoritedSet.contains(m.getId()),
                null));
    }

    /**
     * 批量 fetch 一组 moments 对应的 users (N+1 fix)
     * 用 findAllById 一条 SQL 拿全
     */
    private Map<Long, User> batchFetchUsers(List<Moment> moments) {
        if (moments.isEmpty()) return Map.of();
        Set<Long> userIds = new HashSet<>();
        for (Moment m : moments) {
            if (m.getUserId() != null) userIds.add(m.getUserId());
        }
        Map<Long, User> map = new HashMap<>();
        for (User u : userRepo.findAllById(userIds)) {
            map.put(u.getId(), u);
        }
        return map;
    }

    /**
     * 批量取 viewer 已 like 的 momentId（feed 列表用，避免 N+1）
     * viewerId 为 null（匿名）或 moments 为空时返回空集，调用端 contains 永远 false。
     */
    private Set<Long> batchFetchLikedIds(Long viewerId, List<Moment> moments) {
        if (viewerId == null || moments.isEmpty()) return Set.of();
        List<Long> ids = moments.stream().map(Moment::getId).toList();
        return new HashSet<>(likeRepo.findLikedMomentIds(viewerId, ids));
    }

    /** 批量取 viewer 已 favorite 的 momentId（feed 列表用，避免 N+1） */
    private Set<Long> batchFetchFavoritedIds(Long viewerId, List<Moment> moments) {
        if (viewerId == null || moments.isEmpty()) return Set.of();
        List<Long> ids = moments.stream().map(Moment::getId).toList();
        return new HashSet<>(favoriteRepo.findFavoritedMomentIds(viewerId, ids));
    }

    /**
     * 同城 / 附近动态查询
     * <p>
     * 算法：
     * 1. 用 lat/lng 矩形 box 在 DB 索引上预过滤（最多 200 条）
     * 2. 内存里做精确 Haversine 距离计算
     * 3. 按距离升序排序
     * 4. 截断到请求的 page/size
     * <p>
     * 半径限制：1km - 200km，超出 clamp。
     */
    @Transactional(readOnly = true)
    public List<MomentDto> listNearby(
            double lat, double lng, double radiusKm, Long viewerId, int page, int size
    ) {
        // 参数边界
        if (lat < -90 || lat > 90) {
            throw new AppException("BAD_PARAM", "纬度越界");
        }
        if (lng < -180 || lng > 180) {
            throw new AppException("BAD_PARAM", "经度越界");
        }
        double radius = Math.max(1.0, Math.min(200.0, radiusKm));
        int safePage = Math.max(0, page);
        int safeSize = Math.min(Math.max(1, size), 50);

        // 1° 纬度 ≈ 111.32 km；经度按 cos(lat) 收缩
        double latDelta = radius / 111.32;
        double lngDelta = radius / (111.32 * Math.max(0.05, Math.cos(Math.toRadians(lat))));

        List<Moment> candidates = momentRepo.findInBoundingBox(
                lat - latDelta, lat + latDelta,
                lng - lngDelta, lng + lngDelta
        );

        // 2. 内存 Haversine 精算
        record Hit(Moment m, double distMeters) {}
        List<Hit> hits = new ArrayList<>();
        for (Moment m : candidates) {
            double d = haversineMeters(lat, lng, m.getLatitude(), m.getLongitude());
            if (d <= radius * 1000.0) {
                hits.add(new Hit(m, d));
            }
        }
        hits.sort((a, b) -> Double.compare(a.distMeters, b.distMeters));

        // 3. 分页截断
        int from = Math.min(safePage * safeSize, hits.size());
        int to = Math.min(from + safeSize, hits.size());
        List<Hit> pageSlice = hits.subList(from, to);

        // 4. N+1 fix: 批量 fetch users
        List<Moment> sliceMoments = pageSlice.stream().map(h -> h.m).toList();
        Map<Long, User> userMap = batchFetchUsers(sliceMoments);
        Set<Long> likedSet = batchFetchLikedIds(viewerId, sliceMoments);
        Set<Long> favoritedSet = batchFetchFavoritedIds(viewerId, sliceMoments);

        // 5. 转 DTO 并填距离
        return pageSlice.stream()
                .map(h -> toDto(h.m, userMap.get(h.m.getUserId()),
                        likedSet.contains(h.m.getId()),
                        favoritedSet.contains(h.m.getId()),
                        h.distMeters))
                .toList();
    }

    /**
     * Haversine 距离公式（米）— 球面距离近似
     * 输入：两点 lat/lng（度）
     * 误差：≤0.5%（地球非完美球体）
     */
    private static double haversineMeters(double lat1, double lng1, double lat2, double lng2) {
        final double R = 6_371_000.0; // 地球半径（米）
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                 + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                 * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return R * c;
    }

    private static Double sanitizeLat(Double lat) {
        if (lat == null) return null;
        if (lat < -90 || lat > 90) return null;
        return lat;
    }

    private static Double sanitizeLng(Double lng) {
        if (lng == null) return null;
        if (lng < -180 || lng > 180) return null;
        return lng;
    }

    @Transactional
    public MomentDto getById(Long momentId, Long viewerId) {
        Moment moment = momentRepo.findById(momentId)
                .orElseThrow(() -> new AppException("MOMENT_NOT_FOUND", "动态不存在"));

        // 作者自己可以看 PENDING_REVIEW / REJECTED（用于审核状态查看）
        // 其他人只能看 PUBLISHED
        boolean isAuthor = viewerId != null && viewerId.equals(moment.getUserId());
        if (!isAuthor && !"PUBLISHED".equals(moment.getStatus())) {
            throw new AppException("MOMENT_NOT_FOUND", "动态不存在");
        }
        if (isAuthor && "DELETED".equals(moment.getStatus())) {
            throw new AppException("MOMENT_NOT_FOUND", "动态不存在");
        }

        // 拉黑过滤 — 任一方向拉黑则视为不存在（不暴露拉黑状态）
        if (viewerId != null && blockService.existsBetween(viewerId, moment.getUserId())) {
            throw new AppException("MOMENT_NOT_FOUND", "动态不存在");
        }

        // 增加浏览数
        moment.setViewCount(moment.getViewCount() + 1);
        momentRepo.save(moment);

        return toDtoWithUser(moment, viewerId);
    }

    @Transactional
    public void delete(Long momentId, Long userId) {
        Moment moment = momentRepo.findById(momentId)
                .orElseThrow(() -> new AppException("MOMENT_NOT_FOUND", "动态不存在"));

        if (!moment.getUserId().equals(userId)) {
            throw new AppException("FORBIDDEN", "无权操作");
        }

        moment.setStatus("DELETED");
        momentRepo.save(moment);

        userRepo.findById(userId).ifPresent(u -> {
            u.setMomentsCount(Math.max(0, u.getMomentsCount() - 1));
            userRepo.save(u);
        });
    }

    public MomentDto toDtoWithUser(Moment m, Long viewerId) {
        User user = userRepo.findById(m.getUserId()).orElse(null);
        // 同事反馈"点赞后重新进来是未点赞状态" = 此处之前 hardcode false
        // 修复：viewerId 非空时查 like/favorite repo,匿名访问保持 false
        boolean liked = viewerId != null
                && likeRepo.existsByUserIdAndMomentId(viewerId, m.getId());
        boolean favorited = viewerId != null
                && favoriteRepo.existsByUserIdAndMomentId(viewerId, m.getId());
        return toDto(m, user, liked, favorited, null);
    }

    public MomentDto toDtoWithDistance(Moment m, Long viewerId, double distMeters) {
        User user = userRepo.findById(m.getUserId()).orElse(null);
        boolean liked = viewerId != null
                && likeRepo.existsByUserIdAndMomentId(viewerId, m.getId());
        boolean favorited = viewerId != null
                && favoriteRepo.existsByUserIdAndMomentId(viewerId, m.getId());
        return toDto(m, user, liked, favorited, distMeters);
    }

    private MomentDto toDto(Moment m, User user, boolean liked, boolean favorited, Double distanceMeters) {
        List<String> mediaUrls = m.getMediaUrls() != null ? m.getMediaUrls() : List.of();

        return new MomentDto(
                m.getId(),
                m.getUserId(),
                user != null ? user.getNickname() : "未知",
                user != null ? user.getAvatarUrl() : null,
                m.getTitle(),
                m.getContent(),
                m.getCoverUrl(),
                mediaUrls,
                m.getHasItem(),
                m.getItemPriceCents(),
                m.getItemAttributes() != null ? m.getItemAttributes() : Map.of(),
                m.getTags() != null ? List.of(m.getTags()) : List.of(),
                m.getLocation(),
                m.getLatitude(),
                m.getLongitude(),
                distanceMeters,
                m.getVisibility(),
                m.getStatus(),
                m.getViewCount(),
                m.getLikeCount(),
                m.getFavoriteCount(),
                m.getCommentCount(),
                m.getChatCount(),
                liked,
                favorited,
                m.getCreatedAt()
        );
    }
}
