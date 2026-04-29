-- ============================================================================
-- V6 · 内容举报系统 (MySQL 8.x)
-- ============================================================================
-- Note: reports 表由 V1__init.sql 创建，这里仅补充缺失的索引（如果有）。
-- 现有列: id/reporter_id/target_type/target_id/reason/description/status/handled_by/handled_at/created_at
-- 默认 status='OPEN'

CREATE INDEX idx_reports_status_created
    ON reports(status, created_at DESC);
