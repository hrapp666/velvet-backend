package com.velvet.backend.config;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;

/**
 * Kafka topic 自动创建配置。
 *
 * <p>仅当 spring.kafka.bootstrap-servers 被显式配置时才生效；
 * Kafka 不可用时应用仍可正常启动。
 */
@Slf4j
@Configuration
@ConditionalOnProperty(name = "spring.kafka.bootstrap-servers")
public class KafkaConfig {

    public static final String TOPIC_ORDERS = "velvet.orders";
    public static final String TOPIC_NOTIFICATIONS = "velvet.notifications";

    @Bean
    public KafkaAdmin.NewTopics velvetTopics() {
        return new KafkaAdmin.NewTopics(
                TopicBuilder.name(TOPIC_ORDERS)
                        .partitions(3)
                        .replicas(1)
                        .build(),
                TopicBuilder.name(TOPIC_NOTIFICATIONS)
                        .partitions(3)
                        .replicas(1)
                        .build()
        );
    }
}
