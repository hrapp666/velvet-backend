package com.velvet.backend.service;

import com.velvet.backend.entity.Order;
import com.velvet.backend.exception.AppException;
import com.velvet.backend.payment.PaymentProvider;
import com.velvet.backend.payment.PaymentProvider.CreateRequest;
import com.velvet.backend.payment.PaymentProvider.CreateResult;
import com.velvet.backend.payment.PaymentProvider.NotifyResult;
import com.velvet.backend.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 支付服务 — 路由到对应的 PaymentProvider
 *
 * <p>平台佣金：6%（可在 application.yml 配置）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

    private final List<PaymentProvider> providers;
    private final OrderRepository orderRepo;
    @Lazy
    private final WalletService walletService;

    @Value("${velvet.payment.commission-rate:0.06}")
    private double commissionRate;

    @Value("${velvet.payment.notify-base-url:https://episodes-paid-loan-threatened.trycloudflare.com}")
    private String notifyBaseUrl;

    private Map<String, PaymentProvider> providerMap;

    private Map<String, PaymentProvider> getProviders() {
        if (providerMap == null) {
            providerMap = providers.stream()
                    .collect(Collectors.toMap(PaymentProvider::name, p -> p));
        }
        return providerMap;
    }

    /**
     * 创建支付
     */
    @Transactional
    public CreateResult createPayment(Long userId, Long orderId, String providerName) {
        Order order = orderRepo.findById(orderId)
                .orElseThrow(() -> new AppException("ORDER_NOT_FOUND", "订单不存在"));
        if (!order.getBuyerId().equals(userId)) {
            throw new AppException("FORBIDDEN", "只有买家能付款");
        }
        if (!"PENDING".equals(order.getStatus())) {
            throw new AppException("INVALID_STATE",
                    "订单状态不允许付款：" + order.getStatus());
        }

        PaymentProvider provider = getProviders().get(providerName);
        if (provider == null) {
            throw new AppException("INVALID_REQUEST",
                    "不支持的支付方式：" + providerName);
        }

        // 计算平台佣金
        long commission = (long) Math.round(order.getPriceCents() * commissionRate);
        order.setCommissionCents(commission);
        order.setPaymentMethod(providerName);

        CreateResult result = provider.createPayment(new CreateRequest(
                order.getId(),
                userId,
                order.getPriceCents(),
                order.getTitleSnapshot(),
                order.getTitleSnapshot(),
                notifyBaseUrl + "/api/v1/payments/notify/" + providerName.toLowerCase(),
                null
        ));
        order.setPaymentId(result.providerOrderId());
        orderRepo.save(order);

        log.info("[pay] created: orderId={} provider={} amount={} commission={}",
                orderId, providerName, order.getPriceCents(), commission);

        return result;
    }

    /**
     * 处理异步支付回调
     */
    @Transactional
    public void handleNotify(String providerName, String body, Map<String, String> headers) {
        PaymentProvider provider = getProviders().get(providerName.toUpperCase());
        if (provider == null) {
            log.warn("[pay-notify] unknown provider: {}", providerName);
            return;
        }
        NotifyResult result = provider.verifyNotify(body, headers);
        if (!result.success()) {
            log.warn("[pay-notify] verify failed: {}", body);
            return;
        }
        // 找到订单
        Order order = orderRepo.findById(extractOrderId(result.outTradeNo()))
                .orElse(null);
        if (order == null) return;
        // 标记 PAID
        markOrderPaid(order);
    }

    /**
     * 模拟支付立即成功（MOCK 用）
     */
    @Transactional
    public void mockMarkPaid(Long userId, Long orderId) {
        Order order = orderRepo.findById(orderId)
                .orElseThrow(() -> new AppException("ORDER_NOT_FOUND", "订单不存在"));
        if (!order.getBuyerId().equals(userId)) {
            throw new AppException("FORBIDDEN", "");
        }
        if (!"PENDING".equals(order.getStatus())) {
            throw new AppException("INVALID_STATE", "订单已处理");
        }
        markOrderPaid(order);
    }

    private void markOrderPaid(Order order) {
        order.setStatus("PAID");
        order.setPaidAt(LocalDateTime.now());
        if (order.getCommissionCents() == null || order.getCommissionCents() == 0) {
            order.setCommissionCents(
                    (long) Math.round(order.getPriceCents() * commissionRate));
        }
        orderRepo.save(order);
        log.info("[pay] order PAID: {} commission={}", order.getId(), order.getCommissionCents());
    }

    private Long extractOrderId(String outTradeNo) {
        // outTradeNo format: vlt_ts_orderId or wx_ts_orderId
        try {
            String[] parts = outTradeNo.split("_");
            return Long.parseLong(parts[parts.length - 1]);
        } catch (Exception e) {
            return null;
        }
    }

    public double getCommissionRate() {
        return commissionRate;
    }
}
