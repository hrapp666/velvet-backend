-- ============================================================================
-- V3 · 订单 + 评价 (MySQL 8.x)
-- ============================================================================

CREATE TABLE IF NOT EXISTS orders (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    moment_id       BIGINT NOT NULL,
    buyer_id        BIGINT NOT NULL,
    seller_id       BIGINT NOT NULL,
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
    paid_at         TIMESTAMP NULL,
    -- 物流
    shipped_at      TIMESTAMP NULL,
    tracking_no     VARCHAR(64),
    received_at     TIMESTAMP NULL,
    confirmed_at    TIMESTAMP NULL,
    -- 退款 / 争议
    refund_reason   TEXT,
    refunded_at     TIMESTAMP NULL,
    -- 备注
    buyer_note      TEXT,
    seller_note     TEXT,
    -- 时间戳
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    canceled_at     TIMESTAMP NULL,
    CONSTRAINT fk_orders_moment FOREIGN KEY (moment_id) REFERENCES moments(id) ON DELETE RESTRICT,
    CONSTRAINT fk_orders_buyer FOREIGN KEY (buyer_id) REFERENCES users(id) ON DELETE RESTRICT,
    CONSTRAINT fk_orders_seller FOREIGN KEY (seller_id) REFERENCES users(id) ON DELETE RESTRICT,
    INDEX idx_orders_buyer (buyer_id, created_at DESC),
    INDEX idx_orders_seller (seller_id, created_at DESC),
    INDEX idx_orders_moment (moment_id),
    INDEX idx_orders_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ----------------------------------------------------------------------------
-- 订单评价（买卖家互评）
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS order_reviews (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id        BIGINT NOT NULL,
    -- 评价方角色：BUYER 或 SELLER
    reviewer_role   VARCHAR(8) NOT NULL,
    reviewer_id     BIGINT NOT NULL,
    -- 1-5 星
    rating          SMALLINT NOT NULL,
    content         TEXT,
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_review_rating CHECK (rating >= 1 AND rating <= 5),
    CONSTRAINT fk_review_order FOREIGN KEY (order_id) REFERENCES orders(id) ON DELETE CASCADE,
    CONSTRAINT fk_review_user FOREIGN KEY (reviewer_id) REFERENCES users(id) ON DELETE CASCADE,
    UNIQUE KEY uk_review_role (order_id, reviewer_role),
    INDEX idx_order_reviews_order (order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
