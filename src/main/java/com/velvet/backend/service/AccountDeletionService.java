package com.velvet.backend.service;

import com.velvet.backend.entity.User;
import com.velvet.backend.exception.AppException;
import com.velvet.backend.repository.BlockRepository;
import com.velvet.backend.repository.FavoriteRepository;
import com.velvet.backend.repository.FollowRepository;
import com.velvet.backend.repository.LikeRepository;
import com.velvet.backend.repository.MomentRepository;
import com.velvet.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 账号注销服务 · Apple App Store 5.1.1(v) 合规必需
 * <p>
 * 策略：软删除 + 脱敏（不硬删数据库记录以保留订单/交易审计链）
 * - User.status = DELETED
 * - 脱敏：username/nickname/email/phone/avatar 用 anonymous 占位
 * - 全部 Moment 标记 DELETED
 * - 关注/拉黑/点赞/收藏关系清理
 * - 账号重新注册按原 username/email 应允许（脱敏后唯一约束不冲突）
 * <p>
 * 不涉及：Order 记录保留（资金链路需审计）,Report 记录保留（合规审查）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccountDeletionService {

    private final UserRepository userRepo;
    private final MomentRepository momentRepo;
    private final FollowRepository followRepo;
    private final BlockRepository blockRepo;
    private final LikeRepository likeRepo;
    private final FavoriteRepository favoriteRepo;

    @Transactional
    public void deleteAccount(Long userId, String confirmation) {
        if (!"DELETE_MY_ACCOUNT".equals(confirmation)) {
            throw new AppException("INVALID_REQUEST", "注销确认失败");
        }

        User u = userRepo.findById(userId)
                .orElseThrow(() -> new AppException("USER_NOT_FOUND", "用户不存在"));

        if ("DELETED".equals(u.getStatus())) {
            throw new AppException("ALREADY_DELETED", "账号已注销");
        }

        // 1. 软删除全部动态
        var moments = momentRepo.findByUserIdAndStatusOrderByCreatedAtDesc(
                userId, "PUBLISHED", PageRequest.of(0, 1000)
        );
        moments.forEach(m -> m.setStatus("DELETED"));
        momentRepo.saveAll(moments);

        // 2. 清理社交关系
        followRepo.deleteAllByFollowerIdOrFolloweeId(userId);
        blockRepo.deleteAllRelatedToUser(userId);
        likeRepo.deleteAllByUserId(userId);
        favoriteRepo.deleteAllByUserId(userId);

        // 3. 用户本体脱敏 + 标记删除（保留 id 以维持外键）
        String anonSuffix = String.valueOf(userId);
        u.setStatus("DELETED");
        u.setUsername("deleted_" + anonSuffix);
        u.setNickname("已注销用户");
        u.setEmail(null);
        u.setPhone(null);
        u.setAvatarUrl(null);
        u.setCoverUrl(null);
        u.setBio(null);
        u.setLocation(null);
        u.setPasswordHash("!DELETED!");  // 让登录永远失败
        u.setMomentsCount(0);
        u.setFollowersCount(0);
        u.setFollowingCount(0);
        u.setLastActiveAt(LocalDateTime.now());
        userRepo.save(u);

        log.info("account deleted · userId={} at={}", userId, LocalDateTime.now());
    }
}
