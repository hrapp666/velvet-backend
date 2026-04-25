package com.velvet.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "notifications")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** LIKE / COMMENT / FOLLOW / DM / FAVORITE / SYSTEM */
    @Column(length = 32, nullable = false)
    private String type;

    @Column(length = 128, nullable = false)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String content;

    @Column(name = "actor_id")
    private Long actorId;

    /** MOMENT / USER / COMMENT */
    @Column(name = "target_type", length = 16)
    private String targetType;

    @Column(name = "target_id")
    private Long targetId;

    @Builder.Default
    @Column(name = "is_read", nullable = false)
    private Boolean isRead = false;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
