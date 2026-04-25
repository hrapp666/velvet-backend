-- V8: wallet 乐观锁 + comments user_id 索引
-- wallet version 列 (JPA @Version 乐观锁)
ALTER TABLE wallets ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;

-- comments.user_id 缺索引 (database-reviewer #7)
CREATE INDEX IF NOT EXISTS idx_comments_user ON comments(user_id);

-- timestamp → timestamptz 迁移 (database-reviewer #6 · 关键时间列)
ALTER TABLE users ALTER COLUMN created_at TYPE TIMESTAMPTZ USING created_at AT TIME ZONE 'UTC';
ALTER TABLE moments ALTER COLUMN created_at TYPE TIMESTAMPTZ USING created_at AT TIME ZONE 'UTC';
ALTER TABLE orders ALTER COLUMN created_at TYPE TIMESTAMPTZ USING created_at AT TIME ZONE 'UTC';
ALTER TABLE wallets ALTER COLUMN created_at TYPE TIMESTAMPTZ USING created_at AT TIME ZONE 'UTC';
ALTER TABLE comments ALTER COLUMN created_at TYPE TIMESTAMPTZ USING created_at AT TIME ZONE 'UTC';
ALTER TABLE messages ALTER COLUMN created_at TYPE TIMESTAMPTZ USING created_at AT TIME ZONE 'UTC';
