package com.velvet.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 商家档案 — 用户认证为 MERCHANT 后才能创建商品
 */
@Entity
@Table(name = "merchants")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class Merchant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, unique = true)
    private Long userId;

    @Column(name = "shop_name", nullable = false, length = 64)
    private String shopName;

    @Column(name = "shop_avatar", columnDefinition = "TEXT")
    private String shopAvatar;

    @Column(name = "shop_cover", columnDefinition = "TEXT")
    private String shopCover;

    @Column(name = "shop_intro", columnDefinition = "TEXT")
    private String shopIntro;

    @Column(name = "contact_name", nullable = false, length = 32)
    private String contactName;

    @Column(name = "contact_phone", nullable = false, length = 32)
    private String contactPhone;

    @Column(name = "contact_wechat", length = 64)
    private String contactWechat;

    @Column(name = "id_card_front", columnDefinition = "TEXT")
    private String idCardFront;

    @Column(name = "id_card_back", columnDefinition = "TEXT")
    private String idCardBack;

    @Column(name = "business_license", columnDefinition = "TEXT")
    private String businessLicense;

    @Column(name = "business_no", length = 64)
    private String businessNo;

    /** PENDING / APPROVED / REJECTED */
    @Builder.Default
    @Column(length = 16, nullable = false)
    private String status = "PENDING";

    @Column(name = "review_note", columnDefinition = "TEXT")
    private String reviewNote;

    @Column(name = "reviewed_by")
    private Long reviewedBy;

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    @Builder.Default
    @Column(precision = 3, scale = 2)
    private BigDecimal rating = BigDecimal.ZERO;

    @Builder.Default
    @Column(name = "sales_count", nullable = false)
    private Integer salesCount = 0;

    // —— V5: 个人卖家收款 —— //
    /** PERSONAL / BUSINESS */
    @Builder.Default
    @Column(name = "seller_type", length = 16)
    private String sellerType = "PERSONAL";

    @Column(name = "personal_real_name", length = 64)
    private String personalRealName;

    @Column(name = "personal_id_no", length = 32)
    private String personalIdNo;

    @Column(name = "receive_alipay", length = 128)
    private String receiveAlipay;

    @Column(name = "receive_wechat", length = 128)
    private String receiveWechat;

    @Column(name = "receive_bank_card", length = 64)
    private String receiveBankCard;

    @Column(name = "receive_bank_name", length = 64)
    private String receiveBankName;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
