package com.velvet.backend.payment;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/**
 * Apple In-App Purchase 支付 provider · iOS 入口
 *
 * <p>架构：
 * <ul>
 *   <li>{@link #createPayment} 客户端调用，返回 StoreKit productId（前端用 in_app_purchase
 *       package 拉起原生付款 sheet）</li>
 *   <li>客户端付款完成 → 上传 receipt → 调用 {@link #verifyNotify}（receipt JWS） →
 *       后端调用 App Store Server API 二次校验 → 标记订单 PAID</li>
 *   <li>{@link #refund} App Store Server Notifications V2 自动回调，无需主动发起</li>
 * </ul>
 *
 * <p>当前版本：入口骨架。真实 App Store Server API 校验 / JWS 解码 / 沙盒 vs 生产
 * 路由放在主人提供 issuerId / keyId / .p8 私钥后接入。
 */
@Slf4j
@Component
public class AppleIapPaymentProvider implements PaymentProvider {

    public static final String NAME = "APPLE_IAP";

    @Value("${velvet.payment.apple.bundle-id:com.velvet.velvet}")
    private String bundleId;

    @Value("${velvet.payment.apple.environment:sandbox}")
    private String environment;

    @Value("${velvet.payment.apple.key-id:}")
    private String keyId;

    @Value("${velvet.payment.apple.issuer-id:}")
    private String issuerId;

    @Value("${velvet.payment.apple.private-key-p8-path:}")
    private String privateKeyPath;

    @Override
    public String name() {
        return NAME;
    }

    /**
     * 客户端先调此方法获取本地订单号 + Apple 商品 productId，然后用
     * in_app_purchase package 调起 StoreKit 付款 UI。
     */
    @Override
    public CreateResult createPayment(CreateRequest req) {
        // outTradeNo 与 mock/wechat/alipay 保持同构，便于 PaymentService 提取 orderId
        String outTradeNo = "vlt_" + System.currentTimeMillis() + "_" + req.orderId();
        // Velvet App Store IAP productId 命名约定：velvet.order.{priceCents}
        // 真正落地需在 App Store Connect 配置每个价格档的 productId
        String productId = "velvet.order." + req.amountCents();

        // payload 给前端：StoreKit productId + bundle-id + 内部 outTradeNo（applicationUsername）
        String payload = String.format(
                "{\"productId\":\"%s\",\"bundleId\":\"%s\",\"applicationUsername\":\"%s\",\"environment\":\"%s\"}",
                productId, bundleId, outTradeNo, environment);

        log.info("[apple-iap] created: order={} amount={} productId={} env={}",
                req.orderId(), req.amountCents(), productId, environment);

        return new CreateResult(
                outTradeNo,
                "apple_" + UUID.randomUUID().toString().substring(0, 16),
                payload,
                "apple_iap"
        );
    }

    /**
     * 客户端在 StoreKit 付款成功后调 /api/v1/payments/verify-apple-receipt，
     * raw 是 unified_receipt（base64 / JWS signedTransactionInfo）。
     *
     * <p>TODO 真实校验：
     * <ol>
     *   <li>解析 JWS（StoreKit 2 signedTransactionInfo）→ 取 transactionId / productId /
     *       bundleId / appAccountToken</li>
     *   <li>验证签名链 (Apple root CA → intermediate → leaf)</li>
     *   <li>校验 bundleId == 配置 bundleId · environment 匹配</li>
     *   <li>调用 App Store Server API GET
     *       /inApps/v1/transactions/{transactionId} 二次确认</li>
     *   <li>幂等性：transactionId 命中已有记录 → 直接返回 success</li>
     * </ol>
     */
    @Override
    public NotifyResult verifyNotify(String raw, Map<String, String> headers) {
        // 骨架版：仅记录入参 + 拒绝（生产前必须接入真校验）
        log.warn("[apple-iap] verifyNotify SKELETON · receipt-len={} · 真实校验待接入 keyId={}",
                raw == null ? 0 : raw.length(), keyId.isEmpty() ? "<unset>" : "set");
        return new NotifyResult(false, null, null, 0L, raw);
    }

    /**
     * Apple 退款由 App Store Server Notifications V2 推送 REFUND 事件，
     * 后端被动接收 · 不主动 call refund API。
     */
    @Override
    public boolean refund(RefundRequest req) {
        log.info("[apple-iap] refund passive · outTradeNo={} amount={} (Apple-controlled)",
                req.outTradeNo(), req.amountCents());
        return false;
    }
}
