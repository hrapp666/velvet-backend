-- ============================================================================
-- V11 · 用户拉黑系统 (Apple App Store 1.2 UGC 合规必需)
-- ============================================================================
-- 设计：
--   - blocker_id 拉黑 blocked_id（单向存储）
--   - 业务层双向过滤（feed/chat/moment 互不可见）
--   - 唯一约束防止重复拉黑

CREATE TABLE IF NOT EXISTS blocks (
    id          BIGSERIAL PRIMARY KEY,
    blocker_id  BIGINT      NOT NULL,
    blocked_id  BIGINT      NOT NULL,
    reason      VARCHAR(256),
    created_at  TIMESTAMP   NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_blocks_blocker_blocked UNIQUE (blocker_id, blocked_id),
    CONSTRAINT ck_blocks_not_self        CHECK (blocker_id <> blocked_id)
);

CREATE INDEX IF NOT EXISTS idx_blocks_blocker ON blocks(blocker_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_blocks_blocked ON blocks(blocked_id);
