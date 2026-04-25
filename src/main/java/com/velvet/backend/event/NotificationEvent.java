package com.velvet.backend.event;

/**
 * 通知领域事件 -- 发布到 Kafka topic "velvet.notifications"
 *
 * @param userId     接收者 ID
 * @param type       通知类型 (LIKE / COMMENT / FOLLOW / ORDER / SYSTEM)
 * @param title      标题
 * @param content    内容
 * @param actorId    触发者 ID
 * @param targetType 目标类型 (MOMENT / ORDER / USER)
 * @param targetId   目标 ID
 */
public record NotificationEvent(
        Long userId,
        String type,
        String title,
        String content,
        Long actorId,
        String targetType,
        Long targetId
) {}
