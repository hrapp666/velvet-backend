package com.velvet.backend.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * v26 苹果合规：支付回调已下线
 * 所有 /api/v1/payments/notify/** 端点统一返回 410 Gone
 */
@RestController
@RequestMapping("/api/v1/payments/notify")
public class PaymentNotifyController {

    @RequestMapping(path = {"", "/**"})
    public ResponseEntity<String> disabled() {
        return ResponseEntity.status(HttpStatus.GONE).body("payment_disabled");
    }
}
