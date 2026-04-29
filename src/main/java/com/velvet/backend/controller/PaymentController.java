package com.velvet.backend.controller;

import com.velvet.backend.exception.AppException;
import com.velvet.backend.payment.PaymentProvider.CreateResult;
import com.velvet.backend.service.PaymentService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;

    /**
     * MOCK 支付是否启用 — 生产环境必须 false
     * 在 application.yml 中配置：velvet.payment.mock-enabled: false
     * 或通过环境变量 VELVET_PAYMENT_MOCK_ENABLED=false 关闭
     */
    @Value("${velvet.payment.mock-enabled:false}")
    private boolean mockEnabled;

    public record CreateRequest(Long orderId, String provider) {}

    public record VerifyAppleReceiptRequest(Long orderId, String receipt, String transactionId) {}

    /**
     * 调起支付 — 返回前端需要的 payload
     * provider: APPLE_IAP / WECHAT / ALIPAY / MOCK（生产禁用 MOCK）
     */
    @PostMapping("/create")
    public CreateResult create(@RequestBody CreateRequest req) {
        if (req.orderId() == null) {
            throw new AppException("INVALID_REQUEST", "缺少 orderId");
        }
        String provider = req.provider() != null ? req.provider() : "APPLE_IAP";
        if ("MOCK".equalsIgnoreCase(provider) && !mockEnabled) {
            throw new AppException("INVALID_REQUEST", "不支持的支付方式");
        }
        return paymentService.createPayment(currentUserId(), req.orderId(), provider);
    }

    /**
     * 验证 Apple StoreKit receipt 并标记订单 PAID
     *
     * <p>iOS 客户端在 StoreKit 付款成功后上传 unified_receipt（JWS）给后端，
     * 走 AppleIapPaymentProvider.verifyNotify → markOrderPaid。
     *
     * <p>骨架版：当前仅占位，真实 App Store Server API 二次校验待
     * keyId/issuerId/.p8 私钥配置后接入。
     */
    @PostMapping("/verify-apple-receipt")
    public Map<String, Object> verifyAppleReceipt(@RequestBody VerifyAppleReceiptRequest req) {
        if (req.orderId() == null || req.receipt() == null || req.receipt().isEmpty()) {
            throw new AppException("INVALID_REQUEST", "缺少 orderId 或 receipt");
        }
        // 委派给 PaymentService（共用 markOrderPaid 路径，保持幂等）
        paymentService.handleNotify("APPLE_IAP", req.receipt(), Map.of(
                "X-Apple-TransactionId", req.transactionId() == null ? "" : req.transactionId(),
                "X-Velvet-OrderId", String.valueOf(req.orderId())
        ));
        return Map.of("status", "ok");
    }

    /**
     * MOCK 立即标记付款成功（仅沙盒用 · 生产环境返回 404）
     */
    @PostMapping("/mock-paid/{orderId}")
    public Map<String, Object> mockPaid(@PathVariable Long orderId) {
        if (!mockEnabled) {
            throw new AppException("NOT_FOUND", "");
        }
        paymentService.mockMarkPaid(currentUserId(), orderId);
        return Map.of("status", "ok");
    }

    /**
     * 配置查询：当前佣金率 + 启用的 provider（MOCK 仅沙盒暴露）
     */
    @GetMapping("/config")
    public Map<String, Object> config() {
        Map<String, Object> m = new HashMap<>();
        m.put("commissionRate", paymentService.getCommissionRate());
        // APPLE_IAP 在 iOS 客户端走专属流；客户端按 Platform.isIOS 自行 narrow
        List<String> providers = new ArrayList<>(List.of("APPLE_IAP", "WECHAT", "ALIPAY"));
        if (mockEnabled) {
            providers.add("MOCK");
        }
        m.put("providers", providers.toArray(new String[0]));
        return m;
    }

    private Long currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof Long id)) {
            throw new AppException("UNAUTHORIZED", "请先登录");
        }
        return id;
    }
}
