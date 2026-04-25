package com.velvet.backend.payment;

import java.util.Map;

/**
 * 支付提供方抽象 — Mock / Wechat / Alipay 三个实现
 *
 * <p>架构：
 * <ul>
 *   <li>{@link #createPayment} 创建支付订单，返回前端拉起支付所需 payload</li>
 *   <li>{@link #verifyNotify} 第三方回调时验证签名 + 提取支付结果</li>
 *   <li>{@link #refund} 发起退款</li>
 * </ul>
 */
public interface PaymentProvider {

    /** WECHAT / ALIPAY / MOCK */
    String name();

    /**
     * 创建支付订单
     *
     * @param req 支付请求
     * @return 包含 outTradeNo + payload (前端拉起支付的参数)
     */
    CreateResult createPayment(CreateRequest req);

    /**
     * 验证第三方异步通知 & 提取结果
     *
     * @param raw 原始 body
     * @param headers HTTP headers
     * @return 验签通过 + 支付结果
     */
    NotifyResult verifyNotify(String raw, Map<String, String> headers);

    /**
     * 发起退款
     */
    boolean refund(RefundRequest req);

    // ==========================================================================
    // DTOs
    // ==========================================================================

    record CreateRequest(
            Long orderId,
            Long userId,
            Long amountCents,
            String subject,
            String description,
            String notifyUrl,
            String returnUrl
    ) {}

    record CreateResult(
            String outTradeNo,    // 我方订单号
            String providerOrderId, // 提供方订单号
            String payload,       // 前端拉起支付的 JSON
            String mode           // 'mock' / 'h5' / 'native' / 'jsapi'
    ) {}

    record NotifyResult(
            boolean success,
            String outTradeNo,
            String providerTradeId,
            Long amountCents,
            String rawData
    ) {}

    record RefundRequest(
            String outTradeNo,
            String providerTradeId,
            Long amountCents,
            String reason
    ) {}
}
