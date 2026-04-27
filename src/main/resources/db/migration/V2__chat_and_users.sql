-- ============================================================================
-- V2 · 私信 (chat) + 用户 profile 编辑 + 增强 search index (MySQL 8.x)
-- ============================================================================
-- 注：conversations / messages 表已在 V1 中创建（含 InnoDB + utf8mb4），
--     此处保留 IF NOT EXISTS 块作为幂等保护，避免老库重新部署时报错。

-- ----------------------------------------------------------------------------
-- 私信会话 (两人之间的对话上下文)
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS conversations (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_a_id       BIGINT NOT NULL,
    user_b_id       BIGINT NOT NULL,
    moment_id       BIGINT,
    last_message_id BIGINT,
    last_message_at TIMESTAMP NULL,
    unread_a        INT NOT NULL DEFAULT 0,
    unread_b        INT NOT NULL DEFAULT 0,
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_conversations_pair (user_a_id, user_b_id, moment_id),
    CONSTRAINT fk_conv_user_a FOREIGN KEY (user_a_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_conv_user_b FOREIGN KEY (user_b_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_conv_moment FOREIGN KEY (moment_id) REFERENCES moments(id) ON DELETE SET NULL,
    INDEX idx_conversations_user_a (user_a_id, last_message_at DESC),
    INDEX idx_conversations_user_b (user_b_id, last_message_at DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ----------------------------------------------------------------------------
-- 私信消息
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS messages (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    conversation_id BIGINT NOT NULL,
    sender_id       BIGINT NOT NULL,
    type            VARCHAR(16) NOT NULL DEFAULT 'TEXT',
    content         TEXT NOT NULL,
    media_url       TEXT,
    ref_moment_id   BIGINT,
    is_read         TINYINT(1) NOT NULL DEFAULT 0,
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_msg_conv FOREIGN KEY (conversation_id) REFERENCES conversations(id) ON DELETE CASCADE,
    CONSTRAINT fk_msg_sender FOREIGN KEY (sender_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_msg_ref_moment FOREIGN KEY (ref_moment_id) REFERENCES moments(id) ON DELETE SET NULL,
    INDEX idx_messages_conv_time (conversation_id, created_at DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ----------------------------------------------------------------------------
-- 全文搜索（MySQL 8 InnoDB FULLTEXT 支持中文 ngram parser）
-- 替代 PG 的 pg_trgm + gin_trgm_ops
-- ----------------------------------------------------------------------------
ALTER TABLE moments ADD FULLTEXT INDEX idx_moments_title_ft (title) WITH PARSER ngram;
ALTER TABLE moments ADD FULLTEXT INDEX idx_moments_content_ft (content) WITH PARSER ngram;
ALTER TABLE users   ADD FULLTEXT INDEX idx_users_nickname_ft (nickname) WITH PARSER ngram;
