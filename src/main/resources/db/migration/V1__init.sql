-- ============================================================================
-- Velvet · 数据库初始化 V1（动态社区版）
-- ============================================================================
-- 形态：小红书式动态发布 + 闲鱼式私聊撮合
-- 平台不参与交易，只做信息流 + 私聊通道 + 收藏关注
-- 商业模式：后续讨论（暂无平台抽成 / 暂无 VIP）
-- ============================================================================

-- ----------------------------------------------------------------------------
-- 1. 用户系统
-- ----------------------------------------------------------------------------
CREATE TABLE users (
    id              BIGSERIAL PRIMARY KEY,
    username        VARCHAR(64)  NOT NULL UNIQUE,
    email           VARCHAR(128) UNIQUE,
    phone           VARCHAR(32)  UNIQUE,
    password_hash   VARCHAR(255) NOT NULL,

    -- 资料
    nickname        VARCHAR(64)  NOT NULL,
    avatar_url      VARCHAR(512),
    cover_url       VARCHAR(512),                          -- 个人主页封面
    bio             TEXT,
    gender          SMALLINT     DEFAULT 0,                -- 0 unknown 1 male 2 female 3 other
    birthday        DATE,
    location        VARCHAR(64),                           -- 城市，可选

    -- 状态
    role            VARCHAR(16)  NOT NULL DEFAULT 'USER',  -- USER / ADMIN
    status          VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',-- ACTIVE / BANNED / DELETED

    -- 信誉（轻量，靠社区互动而非订单）
    moments_count   INTEGER      NOT NULL DEFAULT 0,
    followers_count INTEGER      NOT NULL DEFAULT 0,
    following_count INTEGER      NOT NULL DEFAULT 0,
    likes_received  INTEGER      NOT NULL DEFAULT 0,

    -- 隐私设置
    is_private      BOOLEAN      NOT NULL DEFAULT FALSE,   -- 私密账号
    accept_dm_from  VARCHAR(16)  NOT NULL DEFAULT 'ALL',   -- ALL / FOLLOWING / NONE

    created_at      TIMESTAMP NOT NULL DEFAULT now(),
    updated_at      TIMESTAMP NOT NULL DEFAULT now(),
    last_active_at  TIMESTAMP
);
CREATE INDEX idx_users_phone ON users(phone);
CREATE INDEX idx_users_email ON users(email);
CREATE INDEX idx_users_active ON users(last_active_at DESC);

-- ----------------------------------------------------------------------------
-- 2. 动态（核心表 — 替代传统电商的 products）
-- ----------------------------------------------------------------------------
CREATE TABLE moments (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,

    -- 内容
    title           VARCHAR(128),                          -- 可选标题
    content         TEXT NOT NULL,                         -- 正文
    cover_url       VARCHAR(512),
    media_urls      JSONB NOT NULL DEFAULT '[]'::jsonb,    -- 图集/视频 [{url, type, w, h}]

    -- 商品标记（动态可挂可不挂物品）
    has_item        BOOLEAN NOT NULL DEFAULT FALSE,
    item_price_cents BIGINT,                               -- 期望价格（仅展示，不参与交易）
    item_attributes JSONB DEFAULT '{}'::jsonb,             -- 物品属性 free-form

    -- 标签 + 分类
    tags            TEXT[] DEFAULT ARRAY[]::TEXT[],
    location        VARCHAR(64),

    -- Feed 控制
    visibility      VARCHAR(16) NOT NULL DEFAULT 'PUBLIC', -- PUBLIC / FOLLOWERS / PRIVATE
    is_pinned       BOOLEAN NOT NULL DEFAULT FALSE,        -- 置顶到个人主页

    -- 状态
    status          VARCHAR(16) NOT NULL DEFAULT 'PUBLISHED',
    -- DRAFT / PUBLISHED / HIDDEN / DELETED

    -- 计数
    view_count      INTEGER NOT NULL DEFAULT 0,
    like_count      INTEGER NOT NULL DEFAULT 0,
    favorite_count  INTEGER NOT NULL DEFAULT 0,
    comment_count   INTEGER NOT NULL DEFAULT 0,
    chat_count      INTEGER NOT NULL DEFAULT 0,            -- 多少人因为这条动态发起私聊

    created_at      TIMESTAMP NOT NULL DEFAULT now(),
    updated_at      TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX idx_moments_user ON moments(user_id, created_at DESC);
CREATE INDEX idx_moments_status ON moments(status, created_at DESC);
CREATE INDEX idx_moments_tags ON moments USING GIN(tags);
CREATE INDEX idx_moments_visibility ON moments(visibility);

-- ----------------------------------------------------------------------------
-- 3. 关注关系
-- ----------------------------------------------------------------------------
CREATE TABLE follows (
    id              BIGSERIAL PRIMARY KEY,
    follower_id     BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    followee_id     BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at      TIMESTAMP NOT NULL DEFAULT now(),
    UNIQUE (follower_id, followee_id)
);
CREATE INDEX idx_follows_follower ON follows(follower_id, created_at DESC);
CREATE INDEX idx_follows_followee ON follows(followee_id, created_at DESC);

-- ----------------------------------------------------------------------------
-- 4. 点赞 / 收藏 / 评论
-- ----------------------------------------------------------------------------
CREATE TABLE likes (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    moment_id       BIGINT NOT NULL REFERENCES moments(id) ON DELETE CASCADE,
    created_at      TIMESTAMP NOT NULL DEFAULT now(),
    UNIQUE (user_id, moment_id)
);
CREATE INDEX idx_likes_moment ON likes(moment_id);
CREATE INDEX idx_likes_user ON likes(user_id, created_at DESC);

CREATE TABLE favorites (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    moment_id       BIGINT NOT NULL REFERENCES moments(id) ON DELETE CASCADE,
    folder          VARCHAR(64) DEFAULT 'default',
    created_at      TIMESTAMP NOT NULL DEFAULT now(),
    UNIQUE (user_id, moment_id)
);
CREATE INDEX idx_favorites_user ON favorites(user_id, created_at DESC);

CREATE TABLE comments (
    id              BIGSERIAL PRIMARY KEY,
    moment_id       BIGINT NOT NULL REFERENCES moments(id) ON DELETE CASCADE,
    user_id         BIGINT NOT NULL REFERENCES users(id),
    parent_id       BIGINT REFERENCES comments(id) ON DELETE CASCADE,
    content         TEXT NOT NULL,
    like_count      INTEGER NOT NULL DEFAULT 0,
    created_at      TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX idx_comments_moment ON comments(moment_id, created_at DESC);
CREATE INDEX idx_comments_parent ON comments(parent_id);

-- ----------------------------------------------------------------------------
-- 5. 私聊（核心 — 撮合通道）
-- ----------------------------------------------------------------------------
CREATE TABLE conversations (
    id              BIGSERIAL PRIMARY KEY,
    user_a_id       BIGINT NOT NULL REFERENCES users(id),
    user_b_id       BIGINT NOT NULL REFERENCES users(id),
    moment_id       BIGINT REFERENCES moments(id),         -- 因哪条动态发起的（可选）
    last_message    TEXT,
    last_message_at TIMESTAMP,
    unread_a        INTEGER NOT NULL DEFAULT 0,
    unread_b        INTEGER NOT NULL DEFAULT 0,
    -- 任一方拉黑 = 关闭会话
    is_blocked_by_a BOOLEAN NOT NULL DEFAULT FALSE,
    is_blocked_by_b BOOLEAN NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMP NOT NULL DEFAULT now(),
    UNIQUE (user_a_id, user_b_id, moment_id)
);
CREATE INDEX idx_conv_a ON conversations(user_a_id, last_message_at DESC);
CREATE INDEX idx_conv_b ON conversations(user_b_id, last_message_at DESC);

CREATE TABLE messages (
    id              BIGSERIAL PRIMARY KEY,
    conversation_id BIGINT NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    sender_id       BIGINT NOT NULL REFERENCES users(id),
    type            VARCHAR(16) NOT NULL DEFAULT 'TEXT',   -- TEXT / IMAGE / VOICE / MOMENT_CARD / SYSTEM
    content         TEXT NOT NULL,
    extra           JSONB,                                 -- 媒体 url / 时长 / 引用 moment
    is_read         BOOLEAN NOT NULL DEFAULT FALSE,
    is_recalled     BOOLEAN NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX idx_messages_conv ON messages(conversation_id, created_at);

-- ----------------------------------------------------------------------------
-- 6. 通知
-- ----------------------------------------------------------------------------
CREATE TABLE notifications (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    type            VARCHAR(32) NOT NULL,
    -- LIKE / COMMENT / FOLLOW / DM / FAVORITE / SYSTEM
    title           VARCHAR(128) NOT NULL,
    content         TEXT,
    actor_id        BIGINT REFERENCES users(id),           -- 触发通知的人
    target_type     VARCHAR(16),                           -- MOMENT / USER / COMMENT
    target_id       BIGINT,
    is_read         BOOLEAN NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX idx_notif_user ON notifications(user_id, is_read, created_at DESC);

-- ----------------------------------------------------------------------------
-- 7. 举报（合规所需）
-- ----------------------------------------------------------------------------
CREATE TABLE reports (
    id              BIGSERIAL PRIMARY KEY,
    reporter_id     BIGINT NOT NULL REFERENCES users(id),
    target_type     VARCHAR(16) NOT NULL,    -- MOMENT / USER / COMMENT / MESSAGE
    target_id       BIGINT NOT NULL,
    reason          VARCHAR(64) NOT NULL,
    description     TEXT,
    status          VARCHAR(16) NOT NULL DEFAULT 'OPEN',
    handled_by      BIGINT REFERENCES users(id),
    handled_at      TIMESTAMP,
    created_at      TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX idx_reports_status ON reports(status);

-- ----------------------------------------------------------------------------
-- 8. 拉黑
-- ----------------------------------------------------------------------------
CREATE TABLE blocks (
    id              BIGSERIAL PRIMARY KEY,
    blocker_id      BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    blocked_id      BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at      TIMESTAMP NOT NULL DEFAULT now(),
    UNIQUE (blocker_id, blocked_id)
);
CREATE INDEX idx_blocks_blocker ON blocks(blocker_id);

-- ----------------------------------------------------------------------------
-- 9. 内容审核 auto-fix 历史（OpenSpace E1-E3 启发）
-- 拒绝的 listing 会被自动 fix 后存档，用于 skill 进化
-- ----------------------------------------------------------------------------
CREATE TABLE moderation_fixes (
    id              BIGSERIAL PRIMARY KEY,
    moment_id       BIGINT REFERENCES moments(id) ON DELETE SET NULL,
    user_id         BIGINT NOT NULL REFERENCES users(id),
    original_text   TEXT NOT NULL,
    fixed_text      TEXT NOT NULL,
    rejection_reason VARCHAR(64) NOT NULL,
    fix_strategy    VARCHAR(32) NOT NULL,    -- KEYWORD_REPLACE / REWRITE / IMAGE_ENHANCE
    applied         BOOLEAN NOT NULL DEFAULT FALSE,
    skill_version   INTEGER NOT NULL DEFAULT 1,            -- E2 FIX / E3 DERIVED 版本号
    created_at      TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX idx_moderation_fixes_user ON moderation_fixes(user_id);
CREATE INDEX idx_moderation_fixes_strategy ON moderation_fixes(fix_strategy);
