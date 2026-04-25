package com.velvet.backend.controller;

import com.velvet.backend.exception.AppException;
import com.velvet.backend.repository.MerchantRepository;
import com.velvet.backend.repository.OrderRepository;
import com.velvet.backend.repository.UserRepository;
import com.velvet.backend.repository.WithdrawalRepository;
import com.velvet.backend.service.AdminGuard;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.Map;

/**
 * 管理员数据看板 · 聚合统计
 */
@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
public class AdminStatsController {

    private final AdminGuard adminGuard;
    private final UserRepository userRepo;
    private final MerchantRepository merchantRepo;
    private final OrderRepository orderRepo;
    private final WithdrawalRepository withdrawalRepo;

    @PersistenceContext
    private EntityManager em;

    /** 总览数据看板 */
    @GetMapping("/stats")
    public Map<String, Object> stats() {
        Long uid = currentUserId();
        adminGuard.ensureAdmin(uid);

        LocalDateTime todayStart = LocalDate.now().atTime(LocalTime.MIN);

        Map<String, Object> m = new HashMap<>();

        // 用户 / 商家总数
        m.put("totalUsers", userRepo.count());
        m.put("totalMerchants",
                ((Number) em.createQuery("SELECT COUNT(m) FROM Merchant m WHERE m.status='APPROVED'")
                        .getSingleResult()).longValue());

        // 今日订单 · 成交 · 佣金
        m.put("todayOrderCount",
                ((Number) em.createQuery(
                        "SELECT COUNT(o) FROM Order o WHERE o.createdAt >= :start")
                        .setParameter("start", todayStart)
                        .getSingleResult()).longValue());

        Object todaySales = em.createQuery(
                "SELECT COALESCE(SUM(o.priceCents),0) FROM Order o " +
                        "WHERE o.paidAt >= :start AND o.status IN ('PAID','SHIPPED','RECEIVED','CONFIRMED')")
                .setParameter("start", todayStart)
                .getSingleResult();
        m.put("todaySalesCents", ((Number) todaySales).longValue());

        Object todayCommission = em.createQuery(
                "SELECT COALESCE(SUM(o.commissionCents),0) FROM Order o " +
                        "WHERE o.paidAt >= :start AND o.status IN ('PAID','SHIPPED','RECEIVED','CONFIRMED')")
                .setParameter("start", todayStart)
                .getSingleResult();
        m.put("todayCommissionCents", ((Number) todayCommission).longValue());

        // 总成交 / 总佣金
        Object allSales = em.createQuery(
                "SELECT COALESCE(SUM(o.priceCents),0) FROM Order o WHERE o.status='CONFIRMED'")
                .getSingleResult();
        m.put("totalSalesCents", ((Number) allSales).longValue());
        Object allCommission = em.createQuery(
                "SELECT COALESCE(SUM(o.commissionCents),0) FROM Order o WHERE o.status='CONFIRMED'")
                .getSingleResult();
        m.put("totalCommissionCents", ((Number) allCommission).longValue());

        // 待审数
        m.put("pendingMerchants",
                ((Number) em.createQuery("SELECT COUNT(m) FROM Merchant m WHERE m.status='PENDING'")
                        .getSingleResult()).longValue());
        m.put("pendingWithdrawals",
                ((Number) em.createQuery("SELECT COUNT(w) FROM Withdrawal w WHERE w.status='PENDING'")
                        .getSingleResult()).longValue());
        m.put("pendingReports",
                ((Number) em.createQuery("SELECT COUNT(r) FROM Report r WHERE r.status='OPEN'")
                        .getSingleResult()).longValue());
        Object pendingPayoutCents = em.createQuery(
                "SELECT COALESCE(SUM(w.amountCents),0) FROM Withdrawal w WHERE w.status='PENDING'")
                .getSingleResult();
        m.put("pendingPayoutCents", ((Number) pendingPayoutCents).longValue());

        return m;
    }

    private Long currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof Long id)) {
            throw new AppException("UNAUTHORIZED", "请先登录");
        }
        return id;
    }
}
