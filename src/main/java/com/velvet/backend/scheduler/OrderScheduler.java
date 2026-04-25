package com.velvet.backend.scheduler;

import com.velvet.backend.entity.Order;
import com.velvet.backend.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 订单定时任务
 * - 超时未支付自动取消（30 分钟）
 * - 超时未确认自动确认收货（7 天）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderScheduler {

    private final OrderRepository orderRepo;

    /**
     * 每 5 分钟扫描超时未支付订单 → 自动取消
     */
    @Scheduled(cron = "0 */5 * * * *")
    @Transactional
    public void cancelExpiredOrders() {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(30);
        List<Order> expired = orderRepo.findByStatusAndCreatedAtBefore("PENDING", cutoff);
        if (expired.isEmpty()) return;

        for (Order order : expired) {
            order.setStatus("CANCELLED");
        }
        orderRepo.saveAll(expired);
        log.info("[cron] 自动取消 {} 笔超时未支付订单", expired.size());
    }

    /**
     * 每小时扫描已发货超 7 天未确认 → 自动确认收货
     */
    @Scheduled(cron = "0 0 * * * *")
    @Transactional
    public void autoConfirmOrders() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(7);
        List<Order> shipped = orderRepo.findByStatusAndShippedAtBefore("SHIPPED", cutoff);
        if (shipped.isEmpty()) return;

        for (Order order : shipped) {
            order.setStatus("CONFIRMED");
            order.setReceivedAt(LocalDateTime.now());
        }
        orderRepo.saveAll(shipped);
        log.info("[cron] 自动确认收货 {} 笔超时订单", shipped.size());
    }
}
