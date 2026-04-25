package com.velvet.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * 内容举报 — 用户对违规内容的举报记录
 */
@Entity
@Table(name = "reports")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class Report {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "reporter_id", nullable = false)
    private Long reporterId;

    /** MOMENT / USER / COMMENT */
    @Column(name = "target_type", length = 16, nullable = false)
    private String targetType;

    @Column(name = "target_id", nullable = false)
    private Long targetId;

    /** SPAM / ADULT / FAKE / HARASSMENT / OTHER */
    @Column(length = 64, nullable = false)
    private String reason;

    /** 详情（DB 列名 description 来自 V1 迁移） */
    @Column(columnDefinition = "TEXT")
    private String description;

    /** OPEN / REVIEWED / RESOLVED / DISMISSED */
    @Builder.Default
    @Column(length = 16, nullable = false)
    private String status = "OPEN";

    @Column(name = "handled_by")
    private Long handledBy;

    @Column(name = "handled_at")
    private LocalDateTime handledAt;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
