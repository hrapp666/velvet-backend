-- V12: blocks 表补 reason 列 (MySQL 8.x)
-- ck_blocks_not_self 约束在 V11 重写时已带上，本文件只补 reason 列
-- (MySQL 8.0.29+ 支持 ADD COLUMN IF NOT EXISTS)

ALTER TABLE blocks ADD COLUMN IF NOT EXISTS reason VARCHAR(256);
