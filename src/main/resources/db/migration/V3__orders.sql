-- ============================================================================
-- V3 · 订单 + 评价
-- ============================================================================

CREATE TABLE IF NOT EXISTS orders (
    id              BIGSERIAL PRIMARY KEY,
    moment_id       BIGINT NOT NULL REFERENCES moments(id) ON DELETE RESTRICT,
    buyer_id        BIGINT NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    seller_id       BIGINT NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    -- 价格快照（防止 moment 改价后历史失真）
    price_cents     BIGINT NOT NULL,
    -- 商品标题快照
    title_snapshot  VARCHAR(255),
    cover_snapshot  TEXT,
    -- 状态机
    -- PENDING → PAID → SHIPPED → RECEIVED → CONFIRMED
    -- PENDING → CANCELED
    -- PAID/SHIPPED/RECEIVED → REFUND_REQ → REFUNDED
    -- ANY → DISPUTE
    status          VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    -- 支付
    payment_method  VARCHAR(16),  -- WECHAT / ALIPAY / MOCK
    payment_id      VARCHAR(128), -- 第三方支付订单号
    paid_at         TIMESTAMP,
    -- 物流
    shipped_at      TIMESTAMP,
    tracking_no     VARCHAR(64),
    received_at     TIMESTAMP,
    confirmed_at    TIMESTAMP,
    -- 退款 / 争议
    refund_reason   TEXT,
    refunded_at     TIMESTAMP,
    -- 备注
    buyer_note      TEXT,
    seller_note     TEXT,
    -- 时间戳
    created_at      TIMESTAMP NOT NULL DEFAULT now(),
    updated_at      TIMESTAMP NOT NULL DEFAULT now(),
    canceled_at     TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_orders_buyer ON orders(buyer_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_orders_seller ON orders(seller_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_orders_moment ON orders(moment_id);
CREATE INDEX IF NOT EXISTS idx_orders_status ON orders(status);

-- ----------------------------------------------------------------------------
-- 订单评价（买卖家互评）
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS order_reviews (
    id              BIGSERIAL PRIMARY KEY,
    order_id        BIGINT NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
    -- 评价方角色：BUYER 或 SELLER
    reviewer_role   VARCHAR(8) NOT NULL,
    reviewer_id     BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    -- 1-5 星
    rating          SMALLINT NOT NULL CHECK (rating >= 1 AND rating <= 5),
    content         TEXT,
    created_at      TIMESTAMP NOT NULL DEFAULT now(),
    UNIQUE (order_id, reviewer_role)
);
CREATE INDEX IF NOT EXISTS idx_order_reviews_order ON order_reviews(order_id);
