package com.velvet.backend.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * v26 苹果合规：订单功能已下线
 * 所有 /api/v1/orders/** 端点统一返回 410 Gone
 */
@RestController
@RequestMapping("/api/v1/orders")
public class OrderController {

    @RequestMapping(path = {"", "/**"})
    public ResponseEntity<Map<String, Object>> disabled() {
        return ResponseEntity.status(HttpStatus.GONE).body(Map.of(
                "code", "FEATURE_DISABLED",
                "message", "订单功能已下线，当前版本仅支持分享好物"
        ));
    }
}
