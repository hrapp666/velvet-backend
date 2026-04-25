-- ============================================================================
-- V4 · 商家认证 + 支付提供方
-- ============================================================================

-- ----------------------------------------------------------------------------
-- 用户加 role 字段（区分个人 / 商家 / 管理员）
-- ----------------------------------------------------------------------------
ALTER TABLE users
    ADD COLUMN IF NOT EXISTS role VARCHAR(16) NOT NULL DEFAULT 'USER',
    ADD COLUMN IF NOT EXISTS merchant_status VARCHAR(16) DEFAULT 'NONE';
-- role: USER (默认个人) / MERCHANT (认证商家) / ADMIN
-- merchant_status: NONE (未申请) / PENDING / APPROVED / REJECTED

CREATE INDEX IF NOT EXISTS idx_users_role ON users(role);

-- ----------------------------------------------------------------------------
-- 商家档案
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS merchants (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT NOT NULL UNIQUE REFERENCES users(id) ON DELETE CASCADE,
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
    reviewed_by     BIGINT REFERENCES users(id),
    reviewed_at     TIMESTAMP,
    -- 数据
    rating          DECIMAL(3,2) DEFAULT 0,
    sales_count     INTEGER NOT NULL DEFAULT 0,
    -- 时间
    created_at      TIMESTAMP NOT NULL DEFAULT now(),
    updated_at      TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_merchants_status ON merchants(status);
CREATE INDEX IF NOT EXISTS idx_merchants_user ON merchants(user_id);

-- ----------------------------------------------------------------------------
-- 支付记录（独立表，订单可有多次支付尝试）
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS payments (
    id              BIGSERIAL PRIMARY KEY,
    order_id        BIGINT NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
    user_id         BIGINT NOT NULL REFERENCES users(id),
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
    notify_at       TIMESTAMP,
    notify_raw      TEXT,
    -- 时间
    created_at      TIMESTAMP NOT NULL DEFAULT now(),
    paid_at         TIMESTAMP,
    failed_at       TIMESTAMP,
    failed_reason   TEXT
);
CREATE INDEX IF NOT EXISTS idx_payments_order ON payments(order_id);
CREATE INDEX IF NOT EXISTS idx_payments_provider_order ON payments(provider_order_id);
CREATE INDEX IF NOT EXISTS idx_payments_user ON payments(user_id);

-- ----------------------------------------------------------------------------
-- 订单加 commission_cents（平台抽佣金额，价格快照计算）
-- ----------------------------------------------------------------------------
ALTER TABLE orders
    ADD COLUMN IF NOT EXISTS commission_cents BIGINT NOT NULL DEFAULT 0;
