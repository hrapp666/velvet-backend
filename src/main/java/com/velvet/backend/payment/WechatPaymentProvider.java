package com.velvet.backend.payment;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 微信支付（服务商分账模式）— 占位实现
 *
 * <p>等主人办下来：
 * <ol>
 *   <li>注册公司 + 微信支付服务商资质</li>
 *   <li>商家入驻时绑定子商户号 (sub_mch_id)</li>
 *   <li>application.yml 配置 mch_id / api_key / cert_path / app_id</li>
 *   <li>引入 com.github.wechatpay-apiv3:wechatpay-java SDK</li>
 *   <li>把下面的 TODO 实现替换</li>
 * </ol>
 *
 * <p>分账规则：
 * <ul>
 *   <li>买家付款 → 主商户账户（平台公司）</li>
 *   <li>异步分账 → 卖家子商户 94% + 平台 6%</li>
 *   <li>微信支付通道费 0.6% 双方各承担</li>
 * </ul>
 */
@Slf4j
@Component
public class WechatPaymentProvider implements PaymentProvider {

    @Value("${velvet.payment.wechat.mch-id:}")
    private String mchId;

    @Value("${velvet.payment.wechat.app-id:}")
    private String appId;

    @Value("${velvet.payment.wechat.api-key:}")
    private String apiKey;

    @Override
    public String name() {
        return "WECHAT";
    }

    @Override
    public CreateResult createPayment(CreateRequest req) {
        if (mchId == null || mchId.isBlank()) {
            log.warn("[wechat-pay] not configured, falling back to mock");
            return new CreateResult(
                    "wx_unconfigured_" + req.orderId(),
                    null,
                    "{\"error\":\"wechat-pay not configured, set velvet.payment.wechat.mch-id\"}",
                    "h5"
            );
        }
        // TODO: 调用微信支付 H5 / JSAPI 下单接口
        // 1. POST https://api.mch.weixin.qq.com/v3/pay/transactions/h5
        // 2. body: { mchid, appid, description, out_trade_no, notify_url, amount, scene_info }
        // 3. 用 V3 RSA-SHA256 签名
        // 4. 返回 h5_url 让前端跳转
        return new CreateResult(
                "wx_" + System.currentTimeMillis() + "_" + req.orderId(),
                null,
                "{\"todo\":\"wechat h5 payment\"}",
                "h5"
        );
    }

    @Override
    public NotifyResult verifyNotify(String raw, Map<String, String> headers) {
        // TODO:
        // 1. 用 Wechatpay-Signature header + apikey 验证 V3 签名
        // 2. 解密 body resource.ciphertext (AES-256-GCM)
        // 3. 提取 out_trade_no / transaction_id / amount.total
        log.info("[wechat-pay] notify received (TODO verify): {}", raw);
        return new NotifyResult(false, null, null, 0L, raw);
    }

    @Override
    public boolean refund(RefundRequest req) {
        // TODO: POST https://api.mch.weixin.qq.com/v3/refund/domestic/refunds
        log.info("[wechat-pay] refund TODO: {}", req);
        return false;
    }
}
