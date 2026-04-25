package com.velvet.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * 订单评价 — 订单完成后买卖家互评
 */
@Entity
@Table(name = "order_reviews")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class OrderReview {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    /** BUYER 或 SELLER */
    @Column(name = "reviewer_role", length = 8, nullable = false)
    private String reviewerRole;

    @Column(name = "reviewer_id", nullable = false)
    private Long reviewerId;

    /** 1-5 星 */
    @Column(nullable = false)
    private Short rating;

    @Column(columnDefinition = "TEXT")
    private String content;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
