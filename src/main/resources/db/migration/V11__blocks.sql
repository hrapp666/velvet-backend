-- ============================================================================
-- V11 · 用户拉黑系统 (Apple App Store 1.2 UGC 合规必需) (MySQL 8.x)
-- ============================================================================
-- 注：blocks 表已在 V1 中创建（uk_blocks_pair / fk / idx_blocks_blocker）。
-- 此处使用 IF NOT EXISTS 保留原始定义作为幂等保护，老库重新部署时不会冲突。
-- 设计：
--   - blocker_id 拉黑 blocked_id（单向存储）
--   - 业务层双向过滤（feed/chat/moment 互不可见）
--   - 唯一约束防止重复拉黑

CREATE TABLE IF NOT EXISTS blocks (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    blocker_id  BIGINT      NOT NULL,
    blocked_id  BIGINT      NOT NULL,
    created_at  TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_blocks_blocker_blocked (blocker_id, blocked_id),
    CONSTRAINT ck_blocks_not_self CHECK (blocker_id <> blocked_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 索引（IF NOT EXISTS 兼容 MySQL 8.0.29+）
CREATE INDEX IF NOT EXISTS idx_blocks_blocker_created ON blocks(blocker_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_blocks_blocked ON blocks(blocked_id);
