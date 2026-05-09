package com.velvet.backend.service;

import com.velvet.backend.dto.MomentRequests.MomentDto;
import com.velvet.backend.entity.Moment;
import com.velvet.backend.entity.User;
import com.velvet.backend.exception.AppException;
import com.velvet.backend.repository.MomentRepository;
import com.velvet.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * v26 苹果合规：先审后发管线
 *
 * <p>用户发布的 moments 默认 PENDING_REVIEW，feed/搜索/同城均不展示，
 * 仅作者自己在个人页能看到。管理员通过本服务批准（→ PUBLISHED）或拒绝（→ REJECTED）。
 *
 * <p>审核动作幂等：重复 approve / reject 不会改计数。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ModerationService {

    private static final String STATUS_PENDING = "PENDING_REVIEW";
    private static final String STATUS_PUBLISHED = "PUBLISHED";
    private static final String STATUS_REJECTED = "REJECTED";

    private final MomentRepository momentRepo;
    private final UserRepository userRepo;
    private final MomentService momentService;

    /**
     * 列出待审核 moments（按提交时间正序：最早提交的优先审）
     */
    @Transactional(readOnly = true)
    public Page<MomentDto> listPending(int page, int size) {
        Pageable pageable = PageRequest.of(page, Math.min(size, 50));
        Page<Moment> moments = momentRepo.findByStatusOrderByCreatedAtAsc(STATUS_PENDING, pageable);
        return moments.map(m -> momentService.toDtoWithUser(m, null));
    }

    /**
     * 批准发布
     * <p>幂等：若已是 PUBLISHED 直接返回；若是 REJECTED 也允许翻案（管理员回拨）
     */
    @Transactional
    public Map<String, Object> approveMoment(Long momentId, Long adminUserId) {
        Moment moment = momentRepo.findById(momentId)
                .orElseThrow(() -> new AppException("MOMENT_NOT_FOUND", "动态不存在"));

        String oldStatus = moment.getStatus();
        if (STATUS_PUBLISHED.equals(oldStatus)) {
            return Map.of("status", STATUS_PUBLISHED, "changed", false);
        }
        if ("DELETED".equals(oldStatus)) {
            throw new AppException("MOMENT_DELETED", "已删除的动态不能审核");
        }

        moment.setStatus(STATUS_PUBLISHED);
        momentRepo.save(moment);

        // 旧状态是 PENDING（即"未计数"）才 +1；REJECTED → PUBLISHED 翻案不重复 +1
        if (STATUS_PENDING.equals(oldStatus)) {
            User author = userRepo.findById(moment.getUserId()).orElse(null);
            if (author != null) {
                author.setMomentsCount(author.getMomentsCount() + 1);
                userRepo.save(author);
            }
        }

        log.info("Moderation approve momentId={} by admin={} oldStatus={}",
                momentId, adminUserId, oldStatus);
        return Map.of("status", STATUS_PUBLISHED, "changed", true, "previousStatus", oldStatus);
    }

    /**
     * 拒绝发布
     * <p>幂等：已是 REJECTED 直接返回。
     * <p>翻案场景：从 PUBLISHED → REJECTED（事后下架）会同步 momentsCount -1。
     */
    @Transactional
    public Map<String, Object> rejectMoment(Long momentId, Long adminUserId, String reason) {
        Moment moment = momentRepo.findById(momentId)
                .orElseThrow(() -> new AppException("MOMENT_NOT_FOUND", "动态不存在"));

        String oldStatus = moment.getStatus();
        if (STATUS_REJECTED.equals(oldStatus)) {
            return Map.of("status", STATUS_REJECTED, "changed", false);
        }
        if ("DELETED".equals(oldStatus)) {
            throw new AppException("MOMENT_DELETED", "已删除的动态不能审核");
        }

        moment.setStatus(STATUS_REJECTED);
        momentRepo.save(moment);

        // 已经计入 momentsCount 的 PUBLISHED → REJECTED 需要 -1
        if (STATUS_PUBLISHED.equals(oldStatus)) {
            userRepo.findById(moment.getUserId()).ifPresent(u -> {
                u.setMomentsCount(Math.max(0, u.getMomentsCount() - 1));
                userRepo.save(u);
            });
        }

        log.info("Moderation reject momentId={} by admin={} oldStatus={} reason={}",
                momentId, adminUserId, oldStatus, reason);
        return Map.of("status", STATUS_REJECTED, "changed", true,
                "previousStatus", oldStatus,
                "reason", reason != null ? reason : "");
    }

    /**
     * 概览：各状态待办数量
     */
    @Transactional(readOnly = true)
    public Map<String, Long> queueStats() {
        long pending = momentRepo.findByStatusOrderByCreatedAtAsc(STATUS_PENDING,
                PageRequest.of(0, 1)).getTotalElements();
        long published = momentRepo.findByStatusOrderByCreatedAtDesc(STATUS_PUBLISHED,
                PageRequest.of(0, 1)).getTotalElements();
        long rejected = momentRepo.findByStatusOrderByCreatedAtDesc(STATUS_REJECTED,
                PageRequest.of(0, 1)).getTotalElements();
        return Map.of(
                "pending", pending,
                "published", published,
                "rejected", rejected
        );
    }
}
