-- ============================================================================
-- V5 · 卖家钱包 + 提现 + 个人卖家收款字段 (MySQL 8.x)
-- ============================================================================

-- 卖家钱包（每用户唯一一行）
CREATE TABLE IF NOT EXISTS wallets (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id         BIGINT NOT NULL UNIQUE,
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
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_wallets_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    INDEX idx_wallets_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 钱包流水（每次变动一条记录，account ledger）
CREATE TABLE IF NOT EXISTS wallet_entries (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id         BIGINT NOT NULL,
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
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_wallet_entries_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    INDEX idx_wallet_entries_user (user_id, created_at DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 提现申请
CREATE TABLE IF NOT EXISTS withdrawals (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id         BIGINT NOT NULL,
    amount_cents    BIGINT NOT NULL,
    -- WECHAT / ALIPAY / BANK
    method          VARCHAR(16) NOT NULL,
    -- 收款账号 (微信号 / 支付宝 / 银行卡号)
    account         VARCHAR(128) NOT NULL,
    account_name    VARCHAR(64) NOT NULL,
    -- PENDING / APPROVED / PAID / REJECTED
    status          VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    review_note     TEXT,
    reviewed_by     BIGINT,
    reviewed_at     TIMESTAMP NULL,
    paid_at         TIMESTAMP NULL,
    -- 第三方流水号（人工/自动提现）
    payout_trade_id VARCHAR(128),
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_withdrawals_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_withdrawals_reviewer FOREIGN KEY (reviewed_by) REFERENCES users(id) ON DELETE SET NULL,
    INDEX idx_withdrawals_user (user_id, created_at DESC),
    INDEX idx_withdrawals_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 订单增加平台佣金快照字段（V4 已添加 commission_cents，此处略过避免 MySQL Duplicate column）

-- 个人卖家收款账号（merchants 表加字段）
ALTER TABLE merchants
    ADD COLUMN seller_type VARCHAR(16) DEFAULT 'PERSONAL',
    ADD COLUMN personal_real_name VARCHAR(64),
    ADD COLUMN personal_id_no VARCHAR(32),
    ADD COLUMN receive_alipay VARCHAR(128),
    ADD COLUMN receive_wechat VARCHAR(128),
    ADD COLUMN receive_bank_card VARCHAR(64),
    ADD COLUMN receive_bank_name VARCHAR(64);
-- seller_type: PERSONAL (个人) / BUSINESS (营业执照商家)
