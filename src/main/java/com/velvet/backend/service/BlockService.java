package com.velvet.backend.service;

import com.velvet.backend.entity.Block;
import com.velvet.backend.entity.User;
import com.velvet.backend.exception.AppException;
import com.velvet.backend.repository.BlockRepository;
import com.velvet.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 用户拉黑服务 · Apple 1.2 UGC 合规。
 * <p>
 * 语义：
 * - block(a,b): a 拉黑 b (UPSERT)
 * - unblock(a,b): a 解除拉黑 b
 * - isBlocked(a,b): a 是否被 b 拉黑,或 a 是否拉黑 b (双向过滤)
 */
@Service
@RequiredArgsConstructor
public class BlockService {

    private final BlockRepository blockRepo;
    private final UserRepository userRepo;

    @Transactional
    public Block block(Long blockerId, Long blockedId, String reason) {
        if (Objects.equals(blockerId, blockedId)) {
            throw new AppException("INVALID_REQUEST", "不能拉黑自己");
        }
        // 目标用户必须存在且未删除
        User target = userRepo.findById(blockedId)
                .orElseThrow(() -> new AppException("USER_NOT_FOUND", "用户不存在"));
        if ("DELETED".equals(target.getStatus())) {
            throw new AppException("USER_NOT_FOUND", "用户不存在");
        }
        // UPSERT
        return blockRepo.findByBlockerIdAndBlockedId(blockerId, blockedId)
                .orElseGet(() -> blockRepo.save(Block.builder()
                        .blockerId(blockerId)
                        .blockedId(blockedId)
                        .reason(reason)
                        .build()));
    }

    @Transactional
    public void unblock(Long blockerId, Long blockedId) {
        blockRepo.deleteByBlockerIdAndBlockedId(blockerId, blockedId);
    }

    @Transactional(readOnly = true)
    public Page<Map<String, Object>> listBlocked(Long blockerId, int page, int size) {
        Page<Block> blocks = blockRepo.findByBlockerIdOrderByCreatedAtDesc(
                blockerId, PageRequest.of(page, Math.min(size, 50))
        );
        return blocks.map(b -> {
            Map<String, Object> row = new java.util.LinkedHashMap<>();
            row.put("id", b.getId());
            row.put("blockedId", b.getBlockedId());
            row.put("reason", b.getReason());
            row.put("createdAt", b.getCreatedAt());
            userRepo.findById(b.getBlockedId()).ifPresent(u -> {
                row.put("nickname", u.getNickname());
                row.put("avatarUrl", u.getAvatarUrl());
                row.put("username", u.getUsername());
            });
            return row;
        });
    }

    /** 当前用户拉黑的 id 列表（单向）— 给 feed / moment 过滤用 */
    @Transactional(readOnly = true)
    public List<Long> blockedIdsOf(Long blockerId) {
        if (blockerId == null) return List.of();
        return blockRepo.findBlockedIds(blockerId);
    }

    /** 双向拉黑 id 列表 — {我拉黑的} ∪ {拉黑我的} */
    @Transactional(readOnly = true)
    public List<Long> mutualBlockedIds(Long uid) {
        if (uid == null) return List.of();
        return blockRepo.findMutualBlockedIds(uid);
    }

    /** 双向存在关系（用于 chat 发送拦截）*/
    @Transactional(readOnly = true)
    public boolean existsBetween(Long a, Long b) {
        if (a == null || b == null || Objects.equals(a, b)) return false;
        return blockRepo.existsBetween(a, b);
    }
}
