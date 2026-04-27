-- ============================================================================
-- Velvet · 数据库初始化 V1（动态社区版 · MySQL 8.x）
-- ============================================================================
-- 形态：小红书式动态发布 + 闲鱼式私聊撮合
-- 平台不参与交易，只做信息流 + 私聊通道 + 收藏关注
-- ============================================================================

-- ----------------------------------------------------------------------------
-- 1. 用户系统
-- ----------------------------------------------------------------------------
CREATE TABLE users (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    username        VARCHAR(64)  NOT NULL UNIQUE,
    email           VARCHAR(128) UNIQUE,
    phone           VARCHAR(32)  UNIQUE,
    password_hash   VARCHAR(255) NOT NULL,

    -- 资料
    nickname        VARCHAR(64)  NOT NULL,
    avatar_url      VARCHAR(512),
    cover_url       VARCHAR(512),
    bio             TEXT,
    gender          SMALLINT     DEFAULT 0,
    birthday        DATE,
    location        VARCHAR(64),

    -- 状态
    role            VARCHAR(16)  NOT NULL DEFAULT 'USER',
    status          VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',

    -- 信誉
    moments_count   INT          NOT NULL DEFAULT 0,
    followers_count INT          NOT NULL DEFAULT 0,
    following_count INT          NOT NULL DEFAULT 0,
    likes_received  INT          NOT NULL DEFAULT 0,

    -- 隐私设置
    is_private      TINYINT(1)   NOT NULL DEFAULT 0,
    accept_dm_from  VARCHAR(16)  NOT NULL DEFAULT 'ALL',

    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    last_active_at  TIMESTAMP    NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE INDEX idx_users_phone ON users(phone);
CREATE INDEX idx_users_email ON users(email);
CREATE INDEX idx_users_active ON users(last_active_at DESC);

-- ----------------------------------------------------------------------------
-- 2. 动态
-- ----------------------------------------------------------------------------
CREATE TABLE moments (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id         BIGINT NOT NULL,

    -- 内容
    title           VARCHAR(128),
    content         TEXT NOT NULL,
    cover_url       VARCHAR(512),
    media_urls      JSON NOT NULL,

    -- 商品标记
    has_item        TINYINT(1) NOT NULL DEFAULT 0,
    item_price_cents BIGINT,
    item_attributes JSON,

    -- 标签 + 分类（MySQL 无原生数组类型 → JSON 数组）
    tags            JSON,
    location        VARCHAR(64),

    -- Feed 控制
    visibility      VARCHAR(16) NOT NULL DEFAULT 'PUBLIC',
    is_pinned       TINYINT(1) NOT NULL DEFAULT 0,

    status          VARCHAR(16) NOT NULL DEFAULT 'PUBLISHED',

    view_count      INT NOT NULL DEFAULT 0,
    like_count      INT NOT NULL DEFAULT 0,
    favorite_count  INT NOT NULL DEFAULT 0,
    comment_count   INT NOT NULL DEFAULT 0,
    chat_count      INT NOT NULL DEFAULT 0,

    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    CONSTRAINT fk_moments_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE INDEX idx_moments_user ON moments(user_id, created_at DESC);
CREATE INDEX idx_moments_status ON moments(status, created_at DESC);
CREATE INDEX idx_moments_visibility ON moments(visibility);
-- MySQL 不支持 GIN(tags) JSON 索引，标签查询通过 JSON_CONTAINS 或全文索引（按需后续增加）

-- ----------------------------------------------------------------------------
-- 3. 关注关系
-- ----------------------------------------------------------------------------
CREATE TABLE follows (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    follower_id     BIGINT NOT NULL,
    followee_id     BIGINT NOT NULL,
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_follows_pair (follower_id, followee_id),
    CONSTRAINT fk_follows_follower FOREIGN KEY (follower_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_follows_followee FOREIGN KEY (followee_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE INDEX idx_follows_follower ON follows(follower_id, created_at DESC);
CREATE INDEX idx_follows_followee ON follows(followee_id, created_at DESC);

-- ----------------------------------------------------------------------------
-- 4. 点赞 / 收藏 / 评论
-- ----------------------------------------------------------------------------
CREATE TABLE likes (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id         BIGINT NOT NULL,
    moment_id       BIGINT NOT NULL,
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_likes_user_moment (user_id, moment_id),
    CONSTRAINT fk_likes_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_likes_moment FOREIGN KEY (moment_id) REFERENCES moments(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE INDEX idx_likes_moment ON likes(moment_id);
CREATE INDEX idx_likes_user ON likes(user_id, created_at DESC);

CREATE TABLE favorites (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id         BIGINT NOT NULL,
    moment_id       BIGINT NOT NULL,
    folder          VARCHAR(64) DEFAULT 'default',
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_favorites_user_moment (user_id, moment_id),
    CONSTRAINT fk_favorites_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_favorites_moment FOREIGN KEY (moment_id) REFERENCES moments(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE INDEX idx_favorites_user ON favorites(user_id, created_at DESC);

CREATE TABLE comments (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    moment_id       BIGINT NOT NULL,
    user_id         BIGINT NOT NULL,
    parent_id       BIGINT NULL,
    content         TEXT NOT NULL,
    like_count      INT NOT NULL DEFAULT 0,
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_comments_moment FOREIGN KEY (moment_id) REFERENCES moments(id) ON DELETE CASCADE,
    CONSTRAINT fk_comments_user FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT fk_comments_parent FOREIGN KEY (parent_id) REFERENCES comments(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE INDEX idx_comments_moment ON comments(moment_id, created_at DESC);
CREATE INDEX idx_comments_parent ON comments(parent_id);

-- ----------------------------------------------------------------------------
-- 5. 私聊
-- ----------------------------------------------------------------------------
CREATE TABLE conversations (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_a_id       BIGINT NOT NULL,
    user_b_id       BIGINT NOT NULL,
    moment_id       BIGINT NULL,
    last_message    TEXT,
    last_message_at TIMESTAMP NULL,
    unread_a        INT NOT NULL DEFAULT 0,
    unread_b        INT NOT NULL DEFAULT 0,
    is_blocked_by_a TINYINT(1) NOT NULL DEFAULT 0,
    is_blocked_by_b TINYINT(1) NOT NULL DEFAULT 0,
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_conversations_triplet (user_a_id, user_b_id, moment_id),
    CONSTRAINT fk_conversations_a FOREIGN KEY (user_a_id) REFERENCES users(id),
    CONSTRAINT fk_conversations_b FOREIGN KEY (user_b_id) REFERENCES users(id),
    CONSTRAINT fk_conversations_moment FOREIGN KEY (moment_id) REFERENCES moments(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE INDEX idx_conv_a ON conversations(user_a_id, last_message_at DESC);
CREATE INDEX idx_conv_b ON conversations(user_b_id, last_message_at DESC);

CREATE TABLE messages (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    conversation_id BIGINT NOT NULL,
    sender_id       BIGINT NOT NULL,
    type            VARCHAR(16) NOT NULL DEFAULT 'TEXT',
    content         TEXT NOT NULL,
    extra           JSON,
    is_read         TINYINT(1) NOT NULL DEFAULT 0,
    is_recalled     TINYINT(1) NOT NULL DEFAULT 0,
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_messages_conv FOREIGN KEY (conversation_id) REFERENCES conversations(id) ON DELETE CASCADE,
    CONSTRAINT fk_messages_sender FOREIGN KEY (sender_id) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE INDEX idx_messages_conv ON messages(conversation_id, created_at);

-- ----------------------------------------------------------------------------
-- 6. 通知
-- ----------------------------------------------------------------------------
CREATE TABLE notifications (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id         BIGINT NOT NULL,
    type            VARCHAR(32) NOT NULL,
    title           VARCHAR(128) NOT NULL,
    content         TEXT,
    actor_id        BIGINT NULL,
    target_type     VARCHAR(16),
    target_id       BIGINT,
    is_read         TINYINT(1) NOT NULL DEFAULT 0,
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_notifications_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_notifications_actor FOREIGN KEY (actor_id) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE INDEX idx_notif_user ON notifications(user_id, is_read, created_at DESC);

-- ----------------------------------------------------------------------------
-- 7. 举报
-- ----------------------------------------------------------------------------
CREATE TABLE reports (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    reporter_id     BIGINT NOT NULL,
    target_type     VARCHAR(16) NOT NULL,
    target_id       BIGINT NOT NULL,
    reason          VARCHAR(64) NOT NULL,
    description     TEXT,
    status          VARCHAR(16) NOT NULL DEFAULT 'OPEN',
    handled_by      BIGINT NULL,
    handled_at      TIMESTAMP NULL,
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_reports_reporter FOREIGN KEY (reporter_id) REFERENCES users(id),
    CONSTRAINT fk_reports_handler FOREIGN KEY (handled_by) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE INDEX idx_reports_status ON reports(status);

-- ----------------------------------------------------------------------------
-- 8. 拉黑
-- ----------------------------------------------------------------------------
CREATE TABLE blocks (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    blocker_id      BIGINT NOT NULL,
    blocked_id      BIGINT NOT NULL,
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_blocks_pair (blocker_id, blocked_id),
    CONSTRAINT fk_blocks_blocker FOREIGN KEY (blocker_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_blocks_blocked FOREIGN KEY (blocked_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE INDEX idx_blocks_blocker ON blocks(blocker_id);

-- ----------------------------------------------------------------------------
-- 9. 内容审核 auto-fix 历史
-- ----------------------------------------------------------------------------
CREATE TABLE moderation_fixes (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    moment_id       BIGINT NULL,
    user_id         BIGINT NOT NULL,
    original_text   TEXT NOT NULL,
    fixed_text      TEXT NOT NULL,
    rejection_reason VARCHAR(64) NOT NULL,
    fix_strategy    VARCHAR(32) NOT NULL,
    applied         TINYINT(1) NOT NULL DEFAULT 0,
    skill_version   INT NOT NULL DEFAULT 1,
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_modfix_moment FOREIGN KEY (moment_id) REFERENCES moments(id) ON DELETE SET NULL,
    CONSTRAINT fk_modfix_user FOREIGN KEY (user_id) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE INDEX idx_moderation_fixes_user ON moderation_fixes(user_id);
CREATE INDEX idx_moderation_fixes_strategy ON moderation_fixes(fix_strategy);
