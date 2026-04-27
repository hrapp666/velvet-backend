package com.velvet.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Moment · 动态（Velvet 核心实体）
 * <p>
 * 形态：小红书式动态发布 + 闲鱼式商品挂载（可选）
 * 平台不参与交易，has_item 仅作为展示标记 + 价格期望
 */
@Entity
@Table(name = "moments")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class Moment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(length = 128)
    private String title;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String content;

    @Column(name = "cover_url", length = 512)
    private String coverUrl;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "media_urls", columnDefinition = "json")
    @Builder.Default
    private List<String> mediaUrls = new ArrayList<>();

    // ----- 商品挂载（可选）-----
    @Builder.Default @Column(name = "has_item", nullable = false)
    private Boolean hasItem = false;

    @Column(name = "item_price_cents")
    private Long itemPriceCents;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "item_attributes", columnDefinition = "json")
    @Builder.Default
    private Map<String, Object> itemAttributes = new HashMap<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "json")
    private String[] tags;

    @Column(length = 64)
    private String location;

    /** 纬度（WGS84，-90 ~ 90），可空 */
    @Column(name = "latitude")
    private Double latitude;

    /** 经度（WGS84，-180 ~ 180），可空 */
    @Column(name = "longitude")
    private Double longitude;

    // ----- 可见性 / Boost -----
    @Builder.Default @Column(length = 16, nullable = false)
    private String visibility = "PUBLIC";

    @Builder.Default @Column(name = "is_pinned", nullable = false)
    private Boolean isPinned = false;

    @Builder.Default @Column(length = 16, nullable = false)
    private String status = "PUBLISHED";

    // ----- 计数 -----
    @Builder.Default @Column(name = "view_count", nullable = false)
    private Integer viewCount = 0;

    @Builder.Default @Column(name = "like_count", nullable = false)
    private Integer likeCount = 0;

    @Builder.Default @Column(name = "favorite_count", nullable = false)
    private Integer favoriteCount = 0;

    @Builder.Default @Column(name = "comment_count", nullable = false)
    private Integer commentCount = 0;

    @Builder.Default @Column(name = "chat_count", nullable = false)
    private Integer chatCount = 0;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
