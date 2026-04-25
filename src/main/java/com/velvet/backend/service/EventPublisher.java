package com.velvet.backend.service;

import com.velvet.backend.config.KafkaConfig;
import com.velvet.backend.event.NotificationEvent;
import com.velvet.backend.event.OrderEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

/**
 * Kafka 事件发布器。
 *
 * <p>Kafka 不可用时 graceful 降级：捕获异常并记录 WARN，不中断业务流程。
 * 调用方（OrderService 等）在 publish 失败时可走同步 fallback。
 */
@Slf4j
@Service
public class EventPublisher {

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final boolean kafkaAvailable;

    public EventPublisher(
            @SuppressWarnings("SpringJavaInjectionPointsAutowiringInspection")
            org.springframework.beans.factory.ObjectProvider<KafkaTemplate<String, Object>> kafkaTemplateProvider
    ) {
        KafkaTemplate<String, Object> resolved = kafkaTemplateProvider.getIfAvailable();
        this.kafkaTemplate = resolved;
        this.kafkaAvailable = resolved != null;
        if (!kafkaAvailable) {
            log.info("[EventPublisher] KafkaTemplate not available -- events will be skipped");
        }
    }

    /**
     * 发布订单事件到 velvet.orders topic。
     *
     * @return true 如果成功发送（异步），false 如果 Kafka 不可用或发送失败
     */
    public boolean publishOrderEvent(OrderEvent event) {
        if (!kafkaAvailable) {
            log.debug("[EventPublisher] Kafka unavailable, skipping order event: {}", event.eventType());
            return false;
        }
        try {
            kafkaTemplate.send(KafkaConfig.TOPIC_ORDERS, String.valueOf(event.orderId()), event)
                    .whenComplete((result, ex) -> {
                        if (ex != null) {
                            log.warn("[EventPublisher] Failed to send order event orderId={} type={}: {}",
                                    event.orderId(), event.eventType(), ex.getMessage());
                        } else {
                            log.debug("[EventPublisher] Order event sent: type={} orderId={} offset={}",
                                    event.eventType(), event.orderId(),
                                    result.getRecordMetadata().offset());
                        }
                    });
            return true;
        } catch (Exception e) {
            log.warn("[EventPublisher] Error publishing order event orderId={}: {}",
                    event.orderId(), e.getMessage());
            return false;
        }
    }

    /**
     * 发布通知事件到 velvet.notifications topic。
     *
     * @return true 如果成功发送（异步），false 如果 Kafka 不可用或发送失败
     */
    public boolean publishNotification(NotificationEvent event) {
        if (!kafkaAvailable) {
            log.debug("[EventPublisher] Kafka unavailable, skipping notification for userId={}", event.userId());
            return false;
        }
        try {
            kafkaTemplate.send(KafkaConfig.TOPIC_NOTIFICATIONS, String.valueOf(event.userId()), event)
                    .whenComplete((result, ex) -> {
                        if (ex != null) {
                            log.warn("[EventPublisher] Failed to send notification userId={}: {}",
                                    event.userId(), ex.getMessage());
                        } else {
                            log.debug("[EventPublisher] Notification event sent: userId={} type={} offset={}",
                                    event.userId(), event.type(),
                                    result.getRecordMetadata().offset());
                        }
                    });
            return true;
        } catch (Exception e) {
            log.warn("[EventPublisher] Error publishing notification userId={}: {}",
                    event.userId(), e.getMessage());
            return false;
        }
    }
}
