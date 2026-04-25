package com.velvet.backend.controller;

import com.velvet.backend.exception.AppException;
import com.velvet.backend.service.AdminGuard;
import com.velvet.backend.service.MerchantService;
import com.velvet.backend.service.MerchantService.ApplyRequest;
import com.velvet.backend.service.MerchantService.MerchantDto;
import com.velvet.backend.service.MerchantService.ReviewRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/merchants")
@RequiredArgsConstructor
public class MerchantController {

    private final MerchantService merchantService;
    private final AdminGuard adminGuard;

    /** 申请商家认证 */
    @PostMapping("/apply")
    public MerchantDto apply(@RequestBody ApplyRequest req) {
        return merchantService.apply(currentUserId(), req);
    }

    /** 我的商家资料 */
    @GetMapping("/me")
    public MerchantDto myMerchant() {
        return merchantService.myMerchant(currentUserId());
    }

    /** 公开商家档案（看店铺）*/
    @GetMapping("/public/user/{userId}")
    public MerchantDto getPublic(@PathVariable Long userId) {
        return merchantService.getPublic(userId);
    }

    // ─── Admin ───────────────────────────────────────

    /** 管理员：列出所有待审核商家 */
    @GetMapping("/admin/pending")
    public List<MerchantDto> listPending() {
        adminGuard.ensureAdmin(currentUserId());
        return merchantService.listPending();
    }

    /** 管理员：审核商家 */
    @PostMapping("/admin/{id}/review")
    public MerchantDto review(@PathVariable Long id, @RequestBody ReviewRequest req) {
        Long uid = currentUserId();
        adminGuard.ensureAdmin(uid);
        return merchantService.review(uid, id, req);
    }

    private Long currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof Long id)) {
            throw new AppException("UNAUTHORIZED", "请先登录");
        }
        return id;
    }
}
