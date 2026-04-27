-- ============================================================================
-- V7 · 动态地理坐标（同城 / 附近 feed） (MySQL 8.x)
-- ----------------------------------------------------------------------------
-- 在 moments 表加 latitude / longitude + 索引
-- 不引入 PostGIS / MySQL Spatial，使用 Haversine + bounding box 预过滤
-- MySQL 不支持 partial index (WHERE 子句)，普通 BTREE 已够用
-- ============================================================================

ALTER TABLE moments
    ADD COLUMN IF NOT EXISTS latitude  DOUBLE,
    ADD COLUMN IF NOT EXISTS longitude DOUBLE;

-- bounding box 预过滤索引（MySQL 不支持 partial WHERE，去掉 WHERE 子句）
CREATE INDEX IF NOT EXISTS idx_moments_lat ON moments (latitude);
CREATE INDEX IF NOT EXISTS idx_moments_lng ON moments (longitude);

-- 列注释（MySQL 用 ALTER TABLE ... MODIFY COLUMN ... COMMENT）
ALTER TABLE moments
    MODIFY COLUMN latitude  DOUBLE COMMENT 'WGS84 纬度（-90 ~ 90），可空：未授权定位时为 NULL',
    MODIFY COLUMN longitude DOUBLE COMMENT 'WGS84 经度（-180 ~ 180），可空：未授权定位时为 NULL';
