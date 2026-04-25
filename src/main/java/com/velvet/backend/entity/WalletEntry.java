package com.velvet.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * 钱包流水（每次余额变动一条 ledger 记录）
 */
@Entity
@Table(name = "wallet_entries")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class WalletEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** INCOME / WITHDRAW / REFUND / COMMISSION / ADJUST */
    @Column(length = 16, nullable = false)
    private String type;

    @Column(name = "order_id")
    private Long orderId;

    @Column(name = "withdrawal_id")
    private Long withdrawalId;

    /** 正数=入账，负数=出账 */
    @Column(name = "delta_cents", nullable = false)
    private Long deltaCents;

    @Column(name = "balance_after_cents", nullable = false)
    private Long balanceAfterCents;

    @Column(columnDefinition = "TEXT")
    private String description;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
