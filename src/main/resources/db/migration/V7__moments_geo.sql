-- ============================================================================
-- V7 · 动态地理坐标（同城 / 附近 feed）
-- ----------------------------------------------------------------------------
-- 在 moments 表加 latitude / longitude + 索引（不引入 PostGIS，
-- 用 Haversine 公式 + bounding box 预过滤即可满足 C2C 同城需求）
-- ============================================================================

ALTER TABLE moments
    ADD COLUMN IF NOT EXISTS latitude  DOUBLE PRECISION,
    ADD COLUMN IF NOT EXISTS longitude DOUBLE PRECISION;

-- bounding box 预过滤索引（lat / lng 各加索引，规划器会做 bitmap and）
CREATE INDEX IF NOT EXISTS idx_moments_lat ON moments (latitude)
    WHERE status = 'PUBLISHED' AND latitude IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_moments_lng ON moments (longitude)
    WHERE status = 'PUBLISHED' AND longitude IS NOT NULL;

COMMENT ON COLUMN moments.latitude  IS 'WGS84 纬度（-90 ~ 90），可空：未授权定位时为 NULL';
COMMENT ON COLUMN moments.longitude IS 'WGS84 经度（-180 ~ 180），可空：未授权定位时为 NULL';
