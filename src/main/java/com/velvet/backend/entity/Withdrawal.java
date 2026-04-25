package com.velvet.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * 提现申请
 *
 * <p>状态机: PENDING → APPROVED → PAID
 *           PENDING → REJECTED
 */
@Entity
@Table(name = "withdrawals")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class Withdrawal {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "amount_cents", nullable = false)
    private Long amountCents;

    /** WECHAT / ALIPAY / BANK */
    @Column(length = 16, nullable = false)
    private String method;

    @Column(length = 128, nullable = false)
    private String account;

    @Column(name = "account_name", length = 64, nullable = false)
    private String accountName;

    @Builder.Default
    @Column(length = 16, nullable = false)
    private String status = "PENDING";

    @Column(name = "review_note", columnDefinition = "TEXT")
    private String reviewNote;

    @Column(name = "reviewed_by")
    private Long reviewedBy;

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    @Column(name = "paid_at")
    private LocalDateTime paidAt;

    @Column(name = "payout_trade_id", length = 128)
    private String payoutTradeId;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
