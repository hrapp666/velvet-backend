package com.velvet.backend.event;

import java.time.Instant;

/**
 * 订单领域事件 -- 发布到 Kafka topic "velvet.orders"
 *
 * @param eventType CREATED / PAID / SHIPPED / CONFIRMED / CANCELLED
 * @param orderId   订单 ID
 * @param buyerId   买家 ID
 * @param sellerId  卖家 ID
 * @param priceCents 价格（分）
 * @param timestamp  事件时间
 */
public record OrderEvent(
        String eventType,
        Long orderId,
        Long buyerId,
        Long sellerId,
        Long priceCents,
        Instant timestamp
) {
    public static OrderEvent of(String eventType, Long orderId,
                                Long buyerId, Long sellerId, Long priceCents) {
        return new OrderEvent(eventType, orderId, buyerId, sellerId, priceCents, Instant.now());
    }
}
