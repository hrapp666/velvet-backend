package com.velvet.backend.controller;

import com.velvet.backend.exception.AppException;
import com.velvet.backend.service.AdminGuard;
import com.velvet.backend.service.WalletService;
import com.velvet.backend.service.WalletService.WalletDto;
import com.velvet.backend.service.WalletService.WithdrawRequest;
import com.velvet.backend.service.WalletService.WithdrawalDto;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/wallet")
@RequiredArgsConstructor
public class WalletController {

    private final WalletService walletService;
    private final AdminGuard adminGuard;

    /** 我的钱包 */
    @GetMapping
    public WalletDto myWallet() {
        return walletService.myWallet(currentUserId());
    }

    /** 申请提现 */
    @PostMapping("/withdraw")
    public WithdrawalDto withdraw(@RequestBody WithdrawRequest req) {
        return walletService.requestWithdraw(currentUserId(), req);
    }

    /** 钱包流水（全部交易记录） */
    @GetMapping("/transactions")
    public Page<WalletService.TransactionDto> transactions(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return walletService.listTransactions(currentUserId(), page, size);
    }

    /** 我的提现记录 */
    @GetMapping("/withdrawals")
    public Page<WithdrawalDto> myWithdrawals(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return walletService.myWithdrawals(currentUserId(), page, size);
    }

    // ─── Admin ───────────────────────────────────────

    /** 管理员：列出所有待审核提现 */
    @GetMapping("/admin/pending")
    public List<WithdrawalDto> listPending() {
        adminGuard.ensureAdmin(currentUserId());
        return walletService.listPendingWithdrawals();
    }

    /** 管理员：审核提现 (approve/reject/paid) */
    @PostMapping("/admin/{id}/review")
    public WithdrawalDto reviewWithdraw(
            @PathVariable Long id,
            @RequestBody Map<String, Object> body
    ) {
        Long uid = currentUserId();
        adminGuard.ensureAdmin(uid);
        boolean approve = Boolean.TRUE.equals(body.get("approve"));
        String note = (String) body.get("note");
        String payoutTradeId = (String) body.get("payoutTradeId");
        return walletService.reviewWithdraw(uid, id, approve, note, payoutTradeId);
    }

    /** 管理员：手动触发 T+7 释放（测试用） */
    @PostMapping("/admin/release/{userId}")
    public Map<String, Object> releasePending(@PathVariable Long userId) {
        adminGuard.ensureAdmin(currentUserId());
        walletService.releasePending(userId);
        return Map.of("status", "ok");
    }

    private Long currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof Long id)) {
            throw new AppException("UNAUTHORIZED", "请先登录");
        }
        return id;
    }
}
