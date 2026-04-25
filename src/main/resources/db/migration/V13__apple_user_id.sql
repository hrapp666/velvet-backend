-- V13 · Apple Sign-In · 给 users 表加 apple_user_id 字段
-- Apple JWT sub claim 为稳定唯一字符串 ID · 同一 Apple 账号在所有设备上一致
-- nullable · 历史用户走密码登录无需此字段
-- unique · 防止同一 Apple ID 创建多个 Velvet 账号
-- 索引在 unique 约束基础上自动创建

ALTER TABLE users
    ADD COLUMN apple_user_id VARCHAR(128);

ALTER TABLE users
    ADD CONSTRAINT uk_users_apple_user_id UNIQUE (apple_user_id);
