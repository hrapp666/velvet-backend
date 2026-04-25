-- ============================================================================
-- V5 · 卖家钱包 + 提现 + 个人卖家收款字段
-- ============================================================================

-- 卖家钱包（每用户唯一一行）
CREATE TABLE IF NOT EXISTS wallets (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT NOT NULL UNIQUE REFERENCES users(id) ON DELETE CASCADE,
    -- 待结算（订单完成后 T+7 天进可提现，防退款）
    pending_cents   BIGINT NOT NULL DEFAULT 0,
    -- 可提现余额
    balance_cents   BIGINT NOT NULL DEFAULT 0,
    -- 累计已提现
    withdrawn_cents BIGINT NOT NULL DEFAULT 0,
    -- 累计销售（含佣金前）
    total_sales_cents BIGINT NOT NULL DEFAULT 0,
    -- 累计平台佣金（卖出去的，被平台扣的）
    total_commission_cents BIGINT NOT NULL DEFAULT 0,
    created_at      TIMESTAMP NOT NULL DEFAULT now(),
    updated_at      TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_wallets_user ON wallets(user_id);

-- 钱包流水（每次变动一条记录，account ledger）
CREATE TABLE IF NOT EXISTS wallet_entries (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    -- INCOME (订单结算入账) / WITHDRAW / REFUND / COMMISSION / ADJUST
    type            VARCHAR(16) NOT NULL,
    -- 关联订单/提现
    order_id        BIGINT,
    withdrawal_id   BIGINT,
    -- 金额（正数=入账，负数=出账）
    delta_cents     BIGINT NOT NULL,
    -- 入账后余额快照
    balance_after_cents BIGINT NOT NULL,
    description     TEXT,
    created_at      TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_wallet_entries_user ON wallet_entries(user_id, created_at DESC);

-- 提现申请
CREATE TABLE IF NOT EXISTS withdrawals (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    amount_cents    BIGINT NOT NULL,
    -- WECHAT / ALIPAY / BANK
    method          VARCHAR(16) NOT NULL,
    -- 收款账号 (微信号 / 支付宝 / 银行卡号)
    account         VARCHAR(128) NOT NULL,
    account_name    VARCHAR(64) NOT NULL,
    -- PENDING / APPROVED / PAID / REJECTED
    status          VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    review_note     TEXT,
    reviewed_by     BIGINT REFERENCES users(id),
    reviewed_at     TIMESTAMP,
    paid_at         TIMESTAMP,
    -- 第三方流水号（人工/自动提现）
    payout_trade_id VARCHAR(128),
    created_at      TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_withdrawals_user ON withdrawals(user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_withdrawals_status ON withdrawals(status);

-- 订单增加平台佣金快照字段（便于审计/结算）
ALTER TABLE orders
    ADD COLUMN IF NOT EXISTS commission_cents BIGINT NOT NULL DEFAULT 0;

-- 个人卖家收款账号（merchants 表加字段）
ALTER TABLE merchants
    ADD COLUMN IF NOT EXISTS seller_type VARCHAR(16) DEFAULT 'PERSONAL',
    ADD COLUMN IF NOT EXISTS personal_real_name VARCHAR(64),
    ADD COLUMN IF NOT EXISTS personal_id_no VARCHAR(32),
    ADD COLUMN IF NOT EXISTS receive_alipay VARCHAR(128),
    ADD COLUMN IF NOT EXISTS receive_wechat VARCHAR(128),
    ADD COLUMN IF NOT EXISTS receive_bank_card VARCHAR(64),
    ADD COLUMN IF NOT EXISTS receive_bank_name VARCHAR(64);
-- seller_type: PERSONAL (个人) / BUSINESS (营业执照商家)
