package com.velvet.backend.payment;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/**
 * Mock 支付 — 沙盒环境用，立即成功
 *
 * <p>等主人办下微信/支付宝商户号后，把 default provider 切到 wechat/alipay 即可。
 */
@Slf4j
@Component
public class MockPaymentProvider implements PaymentProvider {

    @Override
    public String name() {
        return "MOCK";
    }

    @Override
    public CreateResult createPayment(CreateRequest req) {
        String outTradeNo = "vlt_" + System.currentTimeMillis() + "_" + req.orderId();
        String providerOrderId = "mock_" + UUID.randomUUID().toString().substring(0, 16);
        log.info("[mock-pay] created: order={} amount={} outTradeNo={}",
                req.orderId(), req.amountCents(), outTradeNo);
        return new CreateResult(
                outTradeNo,
                providerOrderId,
                "{\"mock\":true,\"autoSuccess\":true}",
                "mock"
        );
    }

    @Override
    public NotifyResult verifyNotify(String raw, Map<String, String> headers) {
        // Mock 不会有真异步通知
        return new NotifyResult(true, null, null, 0L, raw);
    }

    @Override
    public boolean refund(RefundRequest req) {
        log.info("[mock-pay] refund: outTradeNo={} amount={}", req.outTradeNo(), req.amountCents());
        return true;
    }
}
