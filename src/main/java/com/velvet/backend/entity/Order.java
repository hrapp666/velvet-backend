package com.velvet.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * 订单 — Velvet C2C 交易闭环核心
 *
 * <p>状态机：
 * <pre>
 *   PENDING → PAID → SHIPPED → RECEIVED → CONFIRMED
 *      ↓       ↓         ↓
 *   CANCELED  REFUND_REQ → REFUNDED
 * </pre>
 */
@Entity
@Table(name = "orders")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "moment_id", nullable = false)
    private Long momentId;

    @Column(name = "buyer_id", nullable = false)
    private Long buyerId;

    @Column(name = "seller_id", nullable = false)
    private Long sellerId;

    @Column(name = "price_cents", nullable = false)
    private Long priceCents;

    @Column(name = "title_snapshot")
    private String titleSnapshot;

    @Column(name = "cover_snapshot", columnDefinition = "TEXT")
    private String coverSnapshot;

    @Builder.Default
    @Column(length = 16, nullable = false)
    private String status = "PENDING";

    @Column(name = "payment_method", length = 16)
    private String paymentMethod;

    /** 平台佣金（分）— 下单时按费率快照，用于结算与审计 */
    @Builder.Default
    @Column(name = "commission_cents", nullable = false)
    private Long commissionCents = 0L;

    @Column(name = "payment_id", length = 128)
    private String paymentId;

    @Column(name = "paid_at")
    private LocalDateTime paidAt;

    @Column(name = "shipped_at")
    private LocalDateTime shippedAt;

    @Column(name = "tracking_no", length = 64)
    private String trackingNo;

    @Column(name = "received_at")
    private LocalDateTime receivedAt;

    @Column(name = "confirmed_at")
    private LocalDateTime confirmedAt;

    @Column(name = "refund_reason", columnDefinition = "TEXT")
    private String refundReason;

    @Column(name = "refunded_at")
    private LocalDateTime refundedAt;

    @Column(name = "buyer_note", columnDefinition = "TEXT")
    private String buyerNote;

    @Column(name = "seller_note", columnDefinition = "TEXT")
    private String sellerNote;

    /** 收货人姓名 */
    @Column(name = "shipping_name", length = 64)
    private String shippingName;

    /** 收货人手机号 */
    @Column(name = "shipping_phone", length = 20)
    private String shippingPhone;

    /** 收货地址 */
    @Column(name = "shipping_address", columnDefinition = "TEXT")
    private String shippingAddress;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "canceled_at")
    private LocalDateTime canceledAt;
}
