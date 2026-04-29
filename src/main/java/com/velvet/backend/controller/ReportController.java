package com.velvet.backend.controller;

import com.velvet.backend.entity.Report;
import com.velvet.backend.exception.AppException;
import com.velvet.backend.repository.ReportRepository;
import com.velvet.backend.service.AdminGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/v1/reports")
@RequiredArgsConstructor
public class ReportController {

    private final ReportRepository reportRepo;
    private final AdminGuard adminGuard;

    public record CreateReportRequest(
            String targetType,  // MOMENT / USER / COMMENT / CHAT
            Long targetId,
            String reason,      // SPAM / ADULT / FAKE / HARASSMENT / OTHER
            String description  // 详情说明
    ) {}

    private static final Set<String> VALID_TYPES =
            Set.of("MOMENT", "USER", "COMMENT", "CHAT");
    private static final Set<String> VALID_REASONS =
            Set.of("SPAM", "ADULT", "FAKE", "HARASSMENT", "OTHER");

    /** 提交举报 */
    @PostMapping
    public Report create(@RequestBody CreateReportRequest req) {
        Long uid = currentUserId();
        if (req.targetType() == null || !VALID_TYPES.contains(req.targetType())) {
            throw new AppException("INVALID_REQUEST", "target type 无效");
        }
        if (req.targetId() == null) {
            throw new AppException("INVALID_REQUEST", "缺少 targetId");
        }
        if (req.reason() == null || !VALID_REASONS.contains(req.reason())) {
            throw new AppException("INVALID_REQUEST", "reason 无效");
        }
        // 防重复举报
        if (reportRepo.existsByReporterIdAndTargetTypeAndTargetId(
                uid, req.targetType(), req.targetId())) {
            throw new AppException("DUPLICATE", "已举报过 · 正在审核");
        }
        return reportRepo.save(Report.builder()
                .reporterId(uid)
                .targetType(req.targetType())
                .targetId(req.targetId())
                .reason(req.reason())
                .description(req.description())
                .status("OPEN")
                .build());
    }

    // ─── Admin ─────────────────────────────────

    /** 管理员：列出待审举报（OPEN 状态）*/
    @GetMapping("/admin/pending")
    public List<Report> listPending() {
        adminGuard.ensureAdmin(currentUserId());
        return reportRepo.findByStatusOrderByCreatedAtDesc("OPEN");
    }

    /** 管理员：处理举报 */
    @PostMapping("/admin/{id}/review")
    public Report review(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        Long uid = currentUserId();
        adminGuard.ensureAdmin(uid);
        Report r = reportRepo.findById(id)
                .orElseThrow(() -> new AppException("NOT_FOUND", "举报不存在"));
        String newStatus = (String) body.getOrDefault("status", "RESOLVED");
        if (!Set.of("REVIEWED", "RESOLVED", "DISMISSED").contains(newStatus)) {
            throw new AppException("INVALID_REQUEST", "status 无效");
        }
        r.setStatus(newStatus);
        r.setDescription(
                r.getDescription() == null
                        ? (String) body.get("note")
                        : r.getDescription() + "\n---\n" + (String) body.getOrDefault("note", ""));
        r.setHandledBy(uid);
        r.setHandledAt(LocalDateTime.now());
        return reportRepo.save(r);
    }

    private Long currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof Long id)) {
            throw new AppException("UNAUTHORIZED", "请先登录");
        }
        return id;
    }
}
