-- ============================================================================
-- V2 · 私信 (chat) + 用户 profile 编辑 + 增强 search index
-- ============================================================================

-- ----------------------------------------------------------------------------
-- 私信会话 (两人之间的对话上下文)
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS conversations (
    id              BIGSERIAL PRIMARY KEY,
    -- 始终保证 user_a_id < user_b_id 来唯一索引一对人
    user_a_id       BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    user_b_id       BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    -- 关联到某条 moment（可选 — 通过物品发起的会话）
    moment_id       BIGINT REFERENCES moments(id) ON DELETE SET NULL,
    last_message_id BIGINT,
    last_message_at TIMESTAMP,
    -- 未读计数（双向）
    unread_a        INTEGER NOT NULL DEFAULT 0,
    unread_b        INTEGER NOT NULL DEFAULT 0,
    created_at      TIMESTAMP NOT NULL DEFAULT now(),
    updated_at      TIMESTAMP NOT NULL DEFAULT now(),
    UNIQUE (user_a_id, user_b_id, moment_id)
);
CREATE INDEX IF NOT EXISTS idx_conversations_user_a ON conversations(user_a_id, last_message_at DESC);
CREATE INDEX IF NOT EXISTS idx_conversations_user_b ON conversations(user_b_id, last_message_at DESC);

-- ----------------------------------------------------------------------------
-- 私信消息
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS messages (
    id              BIGSERIAL PRIMARY KEY,
    conversation_id BIGINT NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    sender_id       BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    -- TEXT / IMAGE / MOMENT_REF / SYSTEM
    type            VARCHAR(16) NOT NULL DEFAULT 'TEXT',
    content         TEXT NOT NULL,
    -- 富媒体 / 引用 moment
    media_url       TEXT,
    ref_moment_id   BIGINT REFERENCES moments(id) ON DELETE SET NULL,
    is_read         BOOLEAN NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_messages_conv_time ON messages(conversation_id, created_at DESC);

-- ----------------------------------------------------------------------------
-- moments 全文搜索辅助索引（PostgreSQL ILIKE 已够 MVP，这里加 trgm 索引提速）
-- ----------------------------------------------------------------------------
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE INDEX IF NOT EXISTS idx_moments_title_trgm
    ON moments USING gin (title gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_moments_content_trgm
    ON moments USING gin (content gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_users_nickname_trgm
    ON users USING gin (nickname gin_trgm_ops);
