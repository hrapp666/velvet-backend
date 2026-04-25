-- V12: blocks 表补 reason 列 + 禁止自拉黑 CHECK 约束
-- V11 用 IF NOT EXISTS 无法 alter 已存在表，此处追加

ALTER TABLE blocks ADD COLUMN IF NOT EXISTS reason VARCHAR(256);

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'ck_blocks_not_self'
    ) THEN
        ALTER TABLE blocks ADD CONSTRAINT ck_blocks_not_self CHECK (blocker_id <> blocked_id);
    END IF;
END $$;
