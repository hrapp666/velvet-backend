package com.velvet.backend.controller;

import com.velvet.backend.dto.MomentRequests.MomentDto;
import com.velvet.backend.exception.AppException;
import com.velvet.backend.service.AdminGuard;
import com.velvet.backend.service.ModerationService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * v26 苹果合规：内容审核后台
 *
 * <p>管理员登录后访问，列出 PENDING_REVIEW 队列，逐条 approve / reject。
 *
 * <p>注意：本接口仅鉴定 ADMIN 角色（{@link AdminGuard}），不暴露给普通用户。
 */
@RestController
@RequestMapping("/api/v1/admin/moderation")
@RequiredArgsConstructor
public class ModerationController {

    private final AdminGuard adminGuard;
    private final ModerationService moderationService;

    /** 待审队列概览 */
    @GetMapping("/stats")
    public Map<String, Long> stats() {
        adminGuard.ensureAdmin(currentUserId());
        return moderationService.queueStats();
    }

    /** 待审 moments 列表（按提交时间正序） */
    @GetMapping("/moments/pending")
    public Page<MomentDto> listPending(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        adminGuard.ensureAdmin(currentUserId());
        return moderationService.listPending(page, size);
    }

    /** 批准发布 */
    @PostMapping("/moments/{id}/approve")
    public Map<String, Object> approve(@PathVariable("id") Long momentId) {
        Long adminId = currentUserId();
        adminGuard.ensureAdmin(adminId);
        return moderationService.approveMoment(momentId, adminId);
    }

    /** 拒绝发布 */
    @PostMapping("/moments/{id}/reject")
    public Map<String, Object> reject(
            @PathVariable("id") Long momentId,
            @RequestBody(required = false) Map<String, Object> body) {
        Long adminId = currentUserId();
        adminGuard.ensureAdmin(adminId);
        String reason = body == null ? null : (String) body.get("reason");
        return moderationService.rejectMoment(momentId, adminId, reason);
    }

    private Long currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof Long id)) {
            throw new AppException("UNAUTHORIZED", "请先登录");
        }
        return id;
    }
}
