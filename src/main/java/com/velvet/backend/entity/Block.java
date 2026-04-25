package com.velvet.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * 用户拉黑关系 — Apple App Store 1.2 UGC 合规必需
 * <p>
 * 单向关系：blocker 拉黑 blocked；过滤作用双向生效（feed/chat/moment 互不可见）。
 */
@Entity
@Table(
        name = "blocks",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_blocks_blocker_blocked",
                columnNames = {"blocker_id", "blocked_id"}
        )
)
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class Block {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "blocker_id", nullable = false)
    private Long blockerId;

    @Column(name = "blocked_id", nullable = false)
    private Long blockedId;

    /** 可选备注 — 仅拉黑者可见 */
    @Column(length = 256)
    private String reason;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
