package com.velvet.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "users")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 64)
    private String username;

    @Column(unique = true, length = 128)
    private String email;

    @Column(unique = true, length = 32)
    private String phone;

    /** Apple Sign-In 唯一 ID (JWT sub claim) · 仅 Apple 登录用户填充 */
    @Column(name = "apple_user_id", unique = true, length = 128)
    private String appleUserId;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    // ----- 资料 -----
    @Column(nullable = false, length = 64)
    private String nickname;

    @Column(name = "avatar_url", length = 512)
    private String avatarUrl;

    @Column(name = "cover_url", length = 512)
    private String coverUrl;

    @Column(columnDefinition = "TEXT")
    private String bio;

    private Short gender;

    private LocalDate birthday;

    @Column(length = 64)
    private String location;

    // ----- 状态 -----
    /** USER (个人) / MERCHANT (认证商家) / ADMIN */
    @Builder.Default
    @Column(length = 16, nullable = false)
    private String role = "USER";

    /** NONE / PENDING / APPROVED / REJECTED */
    @Builder.Default
    @Column(name = "merchant_status", length = 16)
    private String merchantStatus = "NONE";

    @Builder.Default
    @Column(length = 16, nullable = false)
    private String status = "ACTIVE";

    // ----- 计数 -----
    @Builder.Default @Column(name = "moments_count", nullable = false)
    private Integer momentsCount = 0;

    @Builder.Default @Column(name = "followers_count", nullable = false)
    private Integer followersCount = 0;

    @Builder.Default @Column(name = "following_count", nullable = false)
    private Integer followingCount = 0;

    @Builder.Default @Column(name = "likes_received", nullable = false)
    private Integer likesReceived = 0;

    // ----- 隐私 -----
    @Builder.Default @Column(name = "is_private", nullable = false)
    private Boolean isPrivate = false;

    @Builder.Default @Column(name = "accept_dm_from", length = 16, nullable = false)
    private String acceptDmFrom = "ALL";

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "last_active_at")
    private LocalDateTime lastActiveAt;
}
