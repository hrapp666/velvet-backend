package com.velvet.backend.service;

import com.velvet.backend.entity.Order;
import com.velvet.backend.entity.Wallet;
import com.velvet.backend.entity.WalletEntry;
import com.velvet.backend.entity.Withdrawal;
import com.velvet.backend.exception.AppException;
import com.velvet.backend.repository.WalletEntryRepository;
import com.velvet.backend.repository.WalletRepository;
import com.velvet.backend.repository.WithdrawalRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Set;

/**
 * 卖家钱包服务
 *
 * <p>资金流转：
 * <pre>
 *   订单 CONFIRMED → 卖家应得 = 订单价 - 佣金 → 进 pendingCents (T+7 释放)
 *   pendingCents → balanceCents (T+7 后)
 *   balanceCents → withdrawnCents (用户申请提现 + 平台审核打款)
 * </pre>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WalletService {

    private final WalletRepository walletRepo;
    private final WalletEntryRepository entryRepo;
    private final WithdrawalRepository withdrawalRepo;
    private final NotificationService notificationService;

    public record WalletDto(
            Long pendingCents,
            Long balanceCents,
            Long withdrawnCents,
            Long totalSalesCents,
            Long totalCommissionCents
    ) {}

    public record WithdrawRequest(
            Long amountCents,
            String method,    // WECHAT / ALIPAY / BANK
            String account,
            String accountName
    ) {}

    public record WithdrawalDto(
            Long id,
            Long amountCents,
            String method,
            String account,
            String accountName,
            String status,
            String reviewNote,
            LocalDateTime createdAt,
            LocalDateTime paidAt
    ) {}

    public record TransactionDto(
            Long id,
            String type,
            Long deltaCents,
            Long balanceAfterCents,
            String description,
            Long orderId,
            Long withdrawalId,
            LocalDateTime createdAt
    ) {}

    /**
     * 获取或创建卖家钱包
     */
    @Transactional
    public Wallet getOrCreate(Long userId) {
        return walletRepo.findByUserId(userId)
                .orElseGet(() -> walletRepo.save(Wallet.builder().userId(userId).build()));
    }

    @Transactional(readOnly = true)
    public WalletDto myWallet(Long userId) {
        Wallet w = walletRepo.findByUserId(userId).orElse(Wallet.builder().userId(userId).build());
        return new WalletDto(
                w.getPendingCents(),
                w.getBalanceCents(),
                w.getWithdrawnCents(),
                w.getTotalSalesCents(),
                w.getTotalCommissionCents()
        );
    }

    /**
     * 订单完成 → 结算给卖家（先入 pending，T+7 后才能提现）
     */
    @Transactional
    public void settleOrder(Order order) {
        if (!"CONFIRMED".equals(order.getStatus())) return;

        Long sellerId = order.getSellerId();
        Long total = order.getPriceCents();
        Long commission = order.getCommissionCents() != null ? order.getCommissionCents() : 0L;
        Long sellerNet = total - commission;

        Wallet w = getOrCreate(sellerId);
        w.setPendingCents(w.getPendingCents() + sellerNet);
        w.setTotalSalesCents(w.getTotalSalesCents() + total);
        w.setTotalCommissionCents(w.getTotalCommissionCents() + commission);
        walletRepo.save(w);

        // 流水
        entryRepo.save(WalletEntry.builder()
                .userId(sellerId)
                .type("INCOME")
                .orderId(order.getId())
                .deltaCents(sellerNet)
                .balanceAfterCents(w.getPendingCents() + w.getBalanceCents())
                .description("订单结算 ¥" + (total / 100.0) + " - 佣金 ¥" + (commission / 100.0))
                .build());

        log.info("[wallet] settle order {}: seller={} net={} commission={}",
                order.getId(), sellerId, sellerNet, commission);

        // 通知卖家
        try {
            notificationService.create(
                    sellerId,
                    "WALLET",
                    "订单结算到账 ¥" + (sellerNet / 100.0),
                    "T+7 天后可提现",
                    null,
                    "ORDER",
                    order.getId());
        } catch (Exception e) {
            log.warn("[notify] failed orderId={}", order.getId(), e);
        }
    }

    /**
     * 释放 T+7 资金（pending → balance）
     * 应当用 cron job 调，这里手动接口也提供
     */
    @Transactional
    public void releasePending(Long userId) {
        Wallet w = walletRepo.findByUserId(userId).orElse(null);
        if (w == null || w.getPendingCents() <= 0) return;
        // MVP: 简化为全部释放（生产应按 entry created_at + 7d 过滤）
        Long released = w.getPendingCents();
        w.setBalanceCents(w.getBalanceCents() + released);
        w.setPendingCents(0L);
        walletRepo.save(w);
        log.info("[wallet] release pending {} for user {}", released, userId);
    }

    /**
     * 申请提现
     */
    @Transactional
    public WithdrawalDto requestWithdraw(Long userId, WithdrawRequest req) {
        if (req.amountCents() == null || req.amountCents() < 1000) {
            throw new AppException("INVALID_REQUEST", "提现金额至少 ¥10");
        }
        if (!Set.of("WECHAT", "ALIPAY", "BANK").contains(req.method())) {
            throw new AppException("INVALID_REQUEST", "不支持的提现方式");
        }
        if (req.account() == null || req.account().isBlank()
                || req.accountName() == null || req.accountName().isBlank()) {
            throw new AppException("INVALID_REQUEST", "请填写收款账号 + 真实姓名");
        }

        Wallet w = getOrCreate(userId);
        if (w.getBalanceCents() < req.amountCents()) {
            throw new AppException("INSUFFICIENT_BALANCE",
                    "可提现余额不足 ¥" + (req.amountCents() / 100.0));
        }

        // 冻结余额
        w.setBalanceCents(w.getBalanceCents() - req.amountCents());
        walletRepo.save(w);

        Withdrawal wd = withdrawalRepo.save(Withdrawal.builder()
                .userId(userId)
                .amountCents(req.amountCents())
                .method(req.method())
                .account(req.account())
                .accountName(req.accountName())
                .status("PENDING")
                .build());

        entryRepo.save(WalletEntry.builder()
                .userId(userId)
                .type("WITHDRAW")
                .withdrawalId(wd.getId())
                .deltaCents(-req.amountCents())
                .balanceAfterCents(w.getPendingCents() + w.getBalanceCents())
                .description("申请提现 ¥" + (req.amountCents() / 100.0) + " 至 " + req.method())
                .build());

        log.info("[wallet] withdraw requested: user={} amount={} method={}",
                userId, req.amountCents(), req.method());

        return toWithdrawalDto(wd);
    }

    /**
     * 钱包流水（全部交易记录）
     */
    @Transactional(readOnly = true)
    public Page<TransactionDto> listTransactions(Long userId, int page, int size) {
        return entryRepo.findByUserIdOrderByCreatedAtDesc(
                userId, PageRequest.of(page, Math.min(size, 50)))
                .map(e -> new TransactionDto(
                        e.getId(),
                        e.getType(),
                        e.getDeltaCents(),
                        e.getBalanceAfterCents(),
                        e.getDescription(),
                        e.getOrderId(),
                        e.getWithdrawalId(),
                        e.getCreatedAt()
                ));
    }

    @Transactional(readOnly = true)
    public Page<WithdrawalDto> myWithdrawals(Long userId, int page, int size) {
        return withdrawalRepo.findByUserIdOrderByCreatedAtDesc(
                userId, PageRequest.of(page, Math.min(size, 50))).map(this::toWithdrawalDto);
    }

    @Transactional(readOnly = true)
    public java.util.List<WithdrawalDto> listPendingWithdrawals() {
        return withdrawalRepo.findByStatusOrderByCreatedAtDesc("PENDING").stream()
                .map(this::toWithdrawalDto)
                .toList();
    }

    /**
     * 后台审核 + 标记已打款
     */
    @Transactional
    public WithdrawalDto reviewWithdraw(Long adminUserId, Long withdrawalId,
                                         boolean approve, String note,
                                         String payoutTradeId) {
        Withdrawal wd = withdrawalRepo.findById(withdrawalId)
                .orElseThrow(() -> new AppException("NOT_FOUND", "提现单不存在"));
        if (!"PENDING".equals(wd.getStatus())) {
            throw new AppException("INVALID_STATE", "已处理");
        }
        if (approve) {
            wd.setStatus("PAID");
            wd.setPaidAt(LocalDateTime.now());
            wd.setPayoutTradeId(payoutTradeId);

            // 真扣减 withdrawn
            Wallet w = walletRepo.findByUserId(wd.getUserId()).orElseThrow();
            w.setWithdrawnCents(w.getWithdrawnCents() + wd.getAmountCents());
            walletRepo.save(w);
        } else {
            wd.setStatus("REJECTED");
            // 退回余额
            Wallet w = walletRepo.findByUserId(wd.getUserId()).orElseThrow();
            w.setBalanceCents(w.getBalanceCents() + wd.getAmountCents());
            walletRepo.save(w);
        }
        wd.setReviewedBy(adminUserId);
        wd.setReviewedAt(LocalDateTime.now());
        wd.setReviewNote(note);
        Withdrawal saved = withdrawalRepo.save(wd);

        // 通知用户
        try {
            double yuan = wd.getAmountCents() / 100.0;
            notificationService.create(
                    wd.getUserId(),
                    "WALLET",
                    approve ? "提现已到账 ¥" + yuan : "提现被驳回 ¥" + yuan,
                    approve
                            ? "平台已打款 · 请留意" + wd.getMethod() + "账户"
                            : (note != null ? note : "金额已退回可提现余额"),
                    adminUserId,
                    "WITHDRAWAL",
                    wd.getId());
        } catch (Exception e) {
            log.warn("[notify] failed withdrawalId={}", wd.getId(), e);
        }

        return toWithdrawalDto(saved);
    }

    private WithdrawalDto toWithdrawalDto(Withdrawal wd) {
        return new WithdrawalDto(
                wd.getId(),
                wd.getAmountCents(),
                wd.getMethod(),
                wd.getAccount(),
                wd.getAccountName(),
                wd.getStatus(),
                wd.getReviewNote(),
                wd.getCreatedAt(),
                wd.getPaidAt()
        );
    }
}
