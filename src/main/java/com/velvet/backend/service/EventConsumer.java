package com.velvet.backend.service;

import com.velvet.backend.event.NotificationEvent;
import com.velvet.backend.event.OrderEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

/**
 * Kafka 事件消费者。
 *
 * <p>仅当 Kafka 可用时才激活（ConditionalOnProperty）。
 * <ul>
 *   <li>velvet.notifications → 调用 NotificationService 落库</li>
 *   <li>velvet.orders → 记录订单状态变更日志（未来扩展: 分析/webhooks）</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "spring.kafka.bootstrap-servers")
public class EventConsumer {

    private final NotificationService notificationService;

    /**
     * 消费通知事件 → 落库。
     */
    @KafkaListener(topics = "velvet.notifications", groupId = "velvet-backend")
    public void onNotification(NotificationEvent event) {
        log.debug("[EventConsumer] Received notification: userId={} type={}", event.userId(), event.type());
        try {
            notificationService.create(
                    event.userId(),
                    event.type(),
                    event.title(),
                    event.content(),
                    event.actorId(),
                    event.targetType(),
                    event.targetId()
            );
        } catch (Exception e) {
            log.error("[EventConsumer] Failed to process notification userId={}: {}",
                    event.userId(), e.getMessage(), e);
        }
    }

    /**
     * 消费订单事件 → 记录日志（未来扩展: 数据分析、webhooks、搜索索引等）。
     */
    @KafkaListener(topics = "velvet.orders", groupId = "velvet-backend")
    public void onOrderEvent(OrderEvent event) {
        log.info("[EventConsumer] Order event: type={} orderId={} buyer={} seller={} price={}",
                event.eventType(), event.orderId(), event.buyerId(),
                event.sellerId(), event.priceCents());
    }
}
