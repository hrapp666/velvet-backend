package com.velvet.backend.controller;

import com.velvet.backend.service.PaymentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 支付异步回调接收端点
 *
 * <p>路由：
 * <ul>
 *   <li>POST /api/v1/payments/notify/wechat — 微信支付 V3 异步通知</li>
 *   <li>POST /api/v1/payments/notify/alipay — 支付宝异步通知</li>
 * </ul>
 *
 * <p>这两个 URL 必须在 SecurityConfig 里 permitAll，因为第三方支付服务器没有 JWT。
 * 实际验签在各自 PaymentProvider.verifyNotify() 中完成。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/payments/notify")
@RequiredArgsConstructor
public class PaymentNotifyController {

    private final PaymentService paymentService;

    /**
     * 微信支付回调
     * 微信 V3 要求返回 {"code":"SUCCESS","message":"成功"}
     */
    @PostMapping("/wechat")
    public Map<String, String> wechatNotify(
            @RequestBody String body,
            @RequestHeader HttpHeaders headers
    ) {
        log.info("[notify/wechat] received: {} bytes", body.length());
        paymentService.handleNotify("WECHAT", body, flatten(headers));
        return Map.of("code", "SUCCESS", "message", "成功");
    }

    /**
     * 支付宝回调
     * 支付宝要求返回 "success" 纯文本
     */
    @PostMapping("/alipay")
    public String alipayNotify(
            @RequestBody String body,
            @RequestHeader HttpHeaders headers
    ) {
        log.info("[notify/alipay] received: {} bytes", body.length());
        paymentService.handleNotify("ALIPAY", body, flatten(headers));
        return "success";
    }

    private Map<String, String> flatten(HttpHeaders headers) {
        Map<String, String> m = new HashMap<>();
        headers.forEach((k, v) -> {
            if (!v.isEmpty()) m.put(k, v.get(0));
        });
        return m;
    }
}
