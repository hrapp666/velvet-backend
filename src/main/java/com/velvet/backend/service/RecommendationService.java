package com.velvet.backend.service;

import com.velvet.backend.dto.MomentRequests.MomentDto;
import com.velvet.backend.entity.Moment;
import com.velvet.backend.entity.User;
import com.velvet.backend.repository.FavoriteRepository;
import com.velvet.backend.repository.LikeRepository;
import com.velvet.backend.repository.MomentRepository;
import com.velvet.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * RecommendationService · v0 content-based tag 相似度推荐
 * <p>
 * 算法（v0 简单版 — Jaccard similarity over tag sets）：
 * 1. 取用户最近 30 条 like + 收藏 moment id（去重）
 * 2. 拉这些 moment 的 tags，合并成 userTagSet
 * 3. 如果 userTagSet 为空 → fallback 返回最新 public moments（冷启动）
 * 4. 拉 candidate pool（最近 200 条 PUBLISHED）
 * 5. 内存里：排除自己发的 + 已 like + 没 tag 的，对每个候选算 jaccard = |overlap| / |union|
 * 6. score 倒序，取前 limit 条；score 相同时按 createdAt 倒序
 * <p>
 * 复杂度：O(P × T)，P = pool size (200)，T = avg tags per moment (~5) → 1000 ops，远低于 1ms。
 * 后续 v1 可加：协同过滤 / 时间衰减 / 多样性惩罚 / 冷热平衡。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RecommendationService {

    /** 用户偏好窗口 — 最近 N 条互动 */
    private static final int USER_HISTORY_WINDOW = 30;

    /** candidate pool 大小 */
    private static final int CANDIDATE_POOL_SIZE = 200;

    /** 单次推荐最大返回条数 */
    private static final int MAX_LIMIT = 50;

    private final MomentRepository momentRepo;
    private final LikeRepository likeRepo;
    private final FavoriteRepository favoriteRepo;
    private final UserRepository userRepo;

    /**
     * 为某用户生成推荐列表。
     *
     * @param userId 当前用户 id（必须）
     * @param limit  返回上限（1 - 50）
     */
    @Transactional(readOnly = true)
    public List<MomentDto> recommendFor(Long userId, int limit) {
        if (userId == null) {
            return List.of();
        }
        int safeLimit = Math.max(1, Math.min(MAX_LIMIT, limit));

        // ── step 1: 收集用户互动历史 momentId ──
        Set<Long> interactedIds = collectInteractedMomentIds(userId);

        // ── step 2: 收集 userTagSet ──
        Set<String> userTagSet = collectUserTagSet(interactedIds);

        // ── step 3: 冷启动 fallback ──
        if (userTagSet.isEmpty()) {
            return fallbackLatest(userId, safeLimit);
        }

        // ── step 4: 拉 candidate pool ──
        List<Moment> pool = momentRepo.findRecentPublishedForRecommend(CANDIDATE_POOL_SIZE);
        if (pool.isEmpty()) {
            return List.of();
        }

        // ── step 5: 排除 + 算 jaccard score ──
        record Scored(Moment m, double score) {}
        List<Scored> scored = new ArrayList<>();
        for (Moment m : pool) {
            if (m.getUserId() == null) continue;
            if (m.getUserId().equals(userId)) continue;       // 排除自己发的
            if (interactedIds.contains(m.getId())) continue;  // 排除已 like / 收藏
            Set<String> momentTags = toTagSet(m.getTags());
            if (momentTags.isEmpty()) continue;               // 没 tag 的没法算 jaccard
            double s = jaccard(userTagSet, momentTags);
            if (s > 0) {
                scored.add(new Scored(m, s));
            }
        }

        // ── step 6: 排序 + 截断 ──
        scored.sort((a, b) -> {
            int cmp = Double.compare(b.score, a.score);
            if (cmp != 0) return cmp;
            // tie-break: 新的优先
            var ta = a.m.getCreatedAt();
            var tb = b.m.getCreatedAt();
            if (ta == null && tb == null) return 0;
            if (ta == null) return 1;
            if (tb == null) return -1;
            return tb.compareTo(ta);
        });

        // v25 reviewer M3 修:scored 全空 → fallback latest
        // 原因:用户可能有品味(userTagSet 非空),但池子里 200 条都无 tag 或都命中 0
        //       这时返回空集会让 UI 显示"还没懂你的品味",误导用户是自己问题
        //       实际是"平台没匹配内容",应该 fallback 给最新 moment
        if (scored.isEmpty()) {
            log.info("No tag matches for user {} in pool · fallback to latest", userId);
            return fallbackLatest(userId, safeLimit);
        }

        // 严格按 tag 匹配返回命中的条目,宁少不滥
        List<Moment> top = new ArrayList<>();
        for (int i = 0; i < Math.min(safeLimit, scored.size()); i++) {
            top.add(scored.get(i).m);
        }

        return toDtoList(top);
    }

    // ─────────────────────────────────────────────────────────
    // helpers
    // ─────────────────────────────────────────────────────────

    private Set<Long> collectInteractedMomentIds(Long userId) {
        Set<Long> ids = new HashSet<>();
        try {
            ids.addAll(likeRepo.findRecentMomentIdsByUserId(
                    userId, PageRequest.of(0, USER_HISTORY_WINDOW)));
            ids.addAll(favoriteRepo.findRecentMomentIdsByUserId(
                    userId, PageRequest.of(0, USER_HISTORY_WINDOW)));
        } catch (RuntimeException e) {
            // 不暴露细节，记录后返回空 history（等价冷启动）
            log.warn("collectInteractedMomentIds failed userId={} reason={}", userId, e.getClass().getSimpleName());
        }
        return ids;
    }

    private Set<String> collectUserTagSet(Collection<Long> momentIds) {
        if (momentIds.isEmpty()) return Set.of();
        Set<String> tags = new HashSet<>();
        try {
            List<Moment> historyMoments = momentRepo.findAllByIdInAndPublished(momentIds);
            for (Moment m : historyMoments) {
                tags.addAll(toTagSet(m.getTags()));
            }
        } catch (RuntimeException e) {
            log.warn("collectUserTagSet failed reason={}", e.getClass().getSimpleName());
        }
        return tags;
    }

    private List<MomentDto> fallbackLatest(Long userId, int limit) {
        List<Moment> pool = momentRepo.findRecentPublishedForRecommend(CANDIDATE_POOL_SIZE);
        List<Moment> picked = new ArrayList<>();
        for (Moment m : pool) {
            if (picked.size() >= limit) break;
            if (m.getUserId() == null || m.getUserId().equals(userId)) continue;
            picked.add(m);
        }
        return toDtoList(picked);
    }

    /**
     * Jaccard similarity over String sets.
     * J(A, B) = |A ∩ B| / |A ∪ B|
     * 输入两边都不含 null 或空字符串（toTagSet 已 normalize）。
     */
    static double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) return 0.0;
        int intersection = 0;
        Set<String> smaller = a.size() <= b.size() ? a : b;
        Set<String> larger = smaller == a ? b : a;
        for (String s : smaller) {
            if (larger.contains(s)) intersection++;
        }
        int union = a.size() + b.size() - intersection;
        if (union == 0) return 0.0;
        return (double) intersection / (double) union;
    }

    /** 把 String[] tags 规范化成 lowercase 去空 set */
    static Set<String> toTagSet(String[] tags) {
        if (tags == null || tags.length == 0) return Set.of();
        Set<String> set = new HashSet<>();
        for (String t : tags) {
            if (t == null) continue;
            String trimmed = t.trim().toLowerCase();
            if (!trimmed.isEmpty()) set.add(trimmed);
        }
        return set;
    }

    /**
     * 批量转 DTO — 复用 MomentService.toDto 的字段映射，但这里独立写避免循环依赖
     * （RecommendationService 不依赖 MomentService）。
     */
    private List<MomentDto> toDtoList(List<Moment> moments) {
        if (moments.isEmpty()) return List.of();
        // 批量 fetch users，避免 N+1
        Set<Long> userIds = new HashSet<>();
        for (Moment m : moments) {
            if (m.getUserId() != null) userIds.add(m.getUserId());
        }
        Map<Long, User> userMap = new HashMap<>();
        for (User u : userRepo.findAllById(userIds)) {
            userMap.put(u.getId(), u);
        }
        List<MomentDto> out = new ArrayList<>(moments.size());
        for (Moment m : moments) {
            out.add(toDto(m, userMap.get(m.getUserId())));
        }
        return out;
    }

    private MomentDto toDto(Moment m, User user) {
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
                null,                    // distanceMeters — 推荐不返回距离
                m.getVisibility(),
                m.getStatus(),
                m.getViewCount(),
                m.getLikeCount(),
                m.getFavoriteCount(),
                m.getCommentCount(),
                m.getChatCount(),
                false,                   // liked — 推荐结果默认未 like（已 like 已被排除）
                false,                   // favorited — 同上
                m.getCreatedAt()
        );
    }
}
