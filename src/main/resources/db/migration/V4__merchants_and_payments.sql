-- ============================================================================
-- V4 · 商家认证 + 支付提供方 (MySQL 8.x · 8.0.29+ 支持 ADD COLUMN IF NOT EXISTS)
-- ============================================================================

-- ----------------------------------------------------------------------------
-- 用户加 merchant_status 字段（role 字段在 V1 已创建）
-- ----------------------------------------------------------------------------
ALTER TABLE users
    ADD COLUMN merchant_status VARCHAR(16) DEFAULT 'NONE';
-- role: USER (默认个人) / MERCHANT (认证商家) / ADMIN  -- V1 已创建
-- merchant_status: NONE (未申请) / PENDING / APPROVED / REJECTED

CREATE INDEX idx_users_role ON users(role);

-- ----------------------------------------------------------------------------
-- 商家档案
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS merchants (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id         BIGINT NOT NULL UNIQUE,
    -- 店铺信息
    shop_name       VARCHAR(64) NOT NULL,
    shop_avatar     TEXT,
    shop_cover      TEXT,
    shop_intro      TEXT,
    -- 联系
    contact_name    VARCHAR(32) NOT NULL,
    contact_phone   VARCHAR(32) NOT NULL,
    contact_wechat  VARCHAR(64),
    -- 实名 / 营业执照
    id_card_front   TEXT,
    id_card_back    TEXT,
    business_license TEXT,
    business_no     VARCHAR(64),
    -- 状态
    status          VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    review_note     TEXT,
    reviewed_by     BIGINT,
    reviewed_at     TIMESTAMP NULL,
    -- 数据
    rating          DECIMAL(3,2) DEFAULT 0,
    sales_count     INT NOT NULL DEFAULT 0,
    -- 时间
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_merchants_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_merchants_reviewer FOREIGN KEY (reviewed_by) REFERENCES users(id) ON DELETE SET NULL,
    INDEX idx_merchants_status (status),
    INDEX idx_merchants_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ----------------------------------------------------------------------------
-- 支付记录（独立表，订单可有多次支付尝试）
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS payments (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id        BIGINT NOT NULL,
    user_id         BIGINT NOT NULL,
    -- 提供方: WECHAT / ALIPAY / MOCK
    provider        VARCHAR(16) NOT NULL,
    -- 状态: CREATED / PAID / FAILED / REFUNDED
    status          VARCHAR(16) NOT NULL DEFAULT 'CREATED',
    -- 金额（分）
    amount_cents    BIGINT NOT NULL,
    -- 平台手续费（分）
    fee_cents       BIGINT NOT NULL DEFAULT 0,
    -- 第三方订单号
    provider_order_id VARCHAR(128),
    provider_trade_id VARCHAR(128),
    -- 拉起支付的 payload (JSON: 支付宝 form / 微信 prepay_id)
    payload         TEXT,
    -- 异步通知
    notify_at       TIMESTAMP NULL,
    notify_raw      TEXT,
    -- 时间
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    paid_at         TIMESTAMP NULL,
    failed_at       TIMESTAMP NULL,
    failed_reason   TEXT,
    CONSTRAINT fk_payments_order FOREIGN KEY (order_id) REFERENCES orders(id) ON DELETE CASCADE,
    CONSTRAINT fk_payments_user FOREIGN KEY (user_id) REFERENCES users(id),
    INDEX idx_payments_order (order_id),
    INDEX idx_payments_provider_order (provider_order_id),
    INDEX idx_payments_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ----------------------------------------------------------------------------
-- 订单加 commission_cents（平台抽佣金额，价格快照计算）
-- ----------------------------------------------------------------------------
ALTER TABLE orders
    ADD COLUMN commission_cents BIGINT NOT NULL DEFAULT 0;
