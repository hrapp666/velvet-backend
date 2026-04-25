package com.velvet.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * 卖家钱包 — 每用户唯一一行
 */
@Entity
@Table(name = "wallets")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class Wallet {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Version
    private Long version;

    @Column(name = "user_id", nullable = false, unique = true)
    private Long userId;

    /** 待结算（订单完成 T+7 天进可提现） */
    @Builder.Default
    @Column(name = "pending_cents", nullable = false)
    private Long pendingCents = 0L;

    /** 可提现余额 */
    @Builder.Default
    @Column(name = "balance_cents", nullable = false)
    private Long balanceCents = 0L;

    /** 累计已提现 */
    @Builder.Default
    @Column(name = "withdrawn_cents", nullable = false)
    private Long withdrawnCents = 0L;

    /** 累计销售额 */
    @Builder.Default
    @Column(name = "total_sales_cents", nullable = false)
    private Long totalSalesCents = 0L;

    /** 累计平台佣金 */
    @Builder.Default
    @Column(name = "total_commission_cents", nullable = false)
    private Long totalCommissionCents = 0L;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
