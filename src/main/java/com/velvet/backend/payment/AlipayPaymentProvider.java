package com.velvet.backend.payment;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 支付宝（直付通分账模式）— 占位实现
 *
 * <p>等主人办下来：
 * <ol>
 *   <li>支付宝开放平台 → 直付通签约</li>
 *   <li>商家入驻时绑定收款账号 (smid)</li>
 *   <li>application.yml 配置 app_id + 私钥/公钥</li>
 *   <li>引入 com.alipay.sdk:alipay-sdk-java SDK</li>
 *   <li>把下面的 TODO 实现替换</li>
 * </ol>
 */
@Slf4j
@Component
public class AlipayPaymentProvider implements PaymentProvider {

    @Value("${velvet.payment.alipay.app-id:}")
    private String appId;

    @Value("${velvet.payment.alipay.private-key:}")
    private String privateKey;

    @Override
    public String name() {
        return "ALIPAY";
    }

    @Override
    public CreateResult createPayment(CreateRequest req) {
        if (appId == null || appId.isBlank()) {
            log.warn("[alipay] not configured, falling back to mock");
            return new CreateResult(
                    "alipay_unconfigured_" + req.orderId(),
                    null,
                    "{\"error\":\"alipay not configured, set velvet.payment.alipay.app-id\"}",
                    "h5"
            );
        }
        // TODO: 调用 alipay.trade.wap.pay (手机网站支付)
        // 1. AlipayClient.pageExecute(request)
        // 2. 返回 form HTML 让浏览器自动 submit 跳支付宝
        return new CreateResult(
                "alipay_" + System.currentTimeMillis() + "_" + req.orderId(),
                null,
                "{\"todo\":\"alipay wap pay\"}",
                "h5"
        );
    }

    @Override
    public NotifyResult verifyNotify(String raw, Map<String, String> headers) {
        // TODO:
        // 1. AlipaySignature.rsaCheckV1(params, alipayPublicKey, charset, signType)
        // 2. 提取 out_trade_no / trade_no / total_amount
        log.info("[alipay] notify received (TODO verify): {}", raw);
        return new NotifyResult(false, null, null, 0L, raw);
    }

    @Override
    public boolean refund(RefundRequest req) {
        // TODO: alipay.trade.refund
        log.info("[alipay] refund TODO: {}", req);
        return false;
    }
}
