package com.velvet.backend.service;

import com.velvet.backend.entity.*;
import com.velvet.backend.exception.AppException;
import com.velvet.backend.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 三个互动业务的统一服务：点赞 / 收藏 / 关注
 *
 * <p>原子化处理：操作互动表 + 更新 moments/users 的计数 + 触发通知
 */
@Service
@RequiredArgsConstructor
public class SocialService {

    private final LikeRepository likeRepo;
    private final FavoriteRepository favoriteRepo;
    private final FollowRepository followRepo;
    private final MomentRepository momentRepo;
    private final UserRepository userRepo;
    private final NotificationService notifService;

    // ─── 点赞 ─────────────────────────────────────────

    @Transactional
    public boolean toggleLike(Long userId, Long momentId) {
        boolean exists = likeRepo.existsByUserIdAndMomentId(userId, momentId);
        if (exists) {
            likeRepo.deleteByUserIdAndMomentId(userId, momentId);
            adjustMomentCount(momentId, "like", -1);
            return false;
        }
        likeRepo.save(Like.builder().userId(userId).momentId(momentId).build());
        adjustMomentCount(momentId, "like", 1);

        // 通知
        Moment m = momentRepo.findById(momentId).orElse(null);
        if (m != null) {
            notifService.create(
                    m.getUserId(), "LIKE", "有人心动了你的动态", null,
                    userId, "MOMENT", momentId
            );
        }
        return true;
    }

    // ─── 收藏 ─────────────────────────────────────────

    @Transactional
    public boolean toggleFavorite(Long userId, Long momentId) {
        boolean exists = favoriteRepo.existsByUserIdAndMomentId(userId, momentId);
        if (exists) {
            favoriteRepo.deleteByUserIdAndMomentId(userId, momentId);
            adjustMomentCount(momentId, "favorite", -1);
            return false;
        }
        favoriteRepo.save(Favorite.builder().userId(userId).momentId(momentId).build());
        adjustMomentCount(momentId, "favorite", 1);

        Moment m = momentRepo.findById(momentId).orElse(null);
        if (m != null) {
            notifService.create(
                    m.getUserId(), "FAVORITE", "有人收藏了你的动态", null,
                    userId, "MOMENT", momentId
            );
        }
        return true;
    }

    // ─── 关注 ─────────────────────────────────────────

    @Transactional
    public boolean toggleFollow(Long followerId, Long followeeId) {
        if (followerId.equals(followeeId)) {
            throw new AppException("INVALID_REQUEST", "不能关注自己");
        }
        boolean exists = followRepo.existsByFollowerIdAndFolloweeId(followerId, followeeId);
        if (exists) {
            followRepo.deleteByFollowerIdAndFolloweeId(followerId, followeeId);
            adjustUserCount(followerId, "following", -1);
            adjustUserCount(followeeId, "followers", -1);
            return false;
        }
        followRepo.save(Follow.builder()
                .followerId(followerId)
                .followeeId(followeeId)
                .build());
        adjustUserCount(followerId, "following", 1);
        adjustUserCount(followeeId, "followers", 1);

        notifService.create(
                followeeId, "FOLLOW", "有人关注了你", null,
                followerId, "USER", followerId
        );
        return true;
    }

    // ─── 工具：原子化更新计数 ─────────────────────────

    private void adjustMomentCount(Long momentId, String field, int delta) {
        momentRepo.findById(momentId).ifPresent(m -> {
            switch (field) {
                case "like" -> m.setLikeCount(Math.max(0, m.getLikeCount() + delta));
                case "favorite" -> m.setFavoriteCount(Math.max(0, m.getFavoriteCount() + delta));
                case "comment" -> m.setCommentCount(Math.max(0, m.getCommentCount() + delta));
                case "chat" -> m.setChatCount(Math.max(0, m.getChatCount() + delta));
                default -> { /* unknown */ }
            }
            momentRepo.save(m);
        });
    }

    private void adjustUserCount(Long userId, String field, int delta) {
        userRepo.findById(userId).ifPresent(u -> {
            switch (field) {
                case "followers" -> u.setFollowersCount(Math.max(0, u.getFollowersCount() + delta));
                case "following" -> u.setFollowingCount(Math.max(0, u.getFollowingCount() + delta));
                case "likes_received" -> u.setLikesReceived(Math.max(0, u.getLikesReceived() + delta));
                case "moments" -> u.setMomentsCount(Math.max(0, u.getMomentsCount() + delta));
                default -> { /* unknown */ }
            }
            userRepo.save(u);
        });
    }
}
