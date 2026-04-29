-- V8: wallet 乐观锁 + comments user_id 索引 (MySQL 8.x)
-- ============================================================================
-- 注：原 PG 版包含 TIMESTAMPTZ 转换；MySQL TIMESTAMP 已经隐式 UTC（参考
-- spring.jpa.properties.hibernate.jdbc.time_zone: UTC + JDBC URL serverTimezone=UTC），
-- 因此跳过类型转换，只保留 wallet.version 列与 comments.user_id 索引。
-- ============================================================================

-- wallet version 列 (JPA @Version 乐观锁)
ALTER TABLE wallets ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

-- comments.user_id 缺索引 (database-reviewer #7)
CREATE INDEX idx_comments_user ON comments(user_id);
