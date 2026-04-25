package com.velvet.backend.controller;

import com.velvet.backend.exception.AppException;
import com.velvet.backend.service.OrderService;
import com.velvet.backend.service.OrderService.CreateOrderRequest;
import com.velvet.backend.service.OrderService.OrderDto;
import com.velvet.backend.service.OrderService.PayRequest;
import com.velvet.backend.service.OrderService.ShipRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    /** 下单 */
    @PostMapping
    public OrderDto create(@RequestBody CreateOrderRequest req) {
        return orderService.create(currentUserId(), req);
    }

    /** 单个订单详情 */
    @GetMapping("/{id}")
    public OrderDto get(@PathVariable Long id) {
        return orderService.getById(currentUserId(), id);
    }

    /** 我作为买家的订单 */
    @GetMapping("/buyer")
    public Page<OrderDto> listAsBuyer(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return orderService.listAsBuyer(currentUserId(), page, size);
    }

    /** 我作为卖家的订单 */
    @GetMapping("/seller")
    public Page<OrderDto> listAsSeller(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return orderService.listAsSeller(currentUserId(), page, size);
    }

    /** 买家付款 */
    @PostMapping("/{id}/pay")
    public OrderDto pay(@PathVariable Long id, @RequestBody PayRequest req) {
        return orderService.pay(currentUserId(), id, req);
    }

    /** 卖家发货 */
    @PostMapping("/{id}/ship")
    public OrderDto ship(@PathVariable Long id, @RequestBody ShipRequest req) {
        return orderService.ship(currentUserId(), id, req);
    }

    /** 买家确认收货 */
    @PostMapping("/{id}/confirm")
    public OrderDto confirm(@PathVariable Long id) {
        return orderService.confirm(currentUserId(), id);
    }

    /** 取消订单（仅 PENDING） */
    @PostMapping("/{id}/cancel")
    public OrderDto cancel(@PathVariable Long id) {
        return orderService.cancel(currentUserId(), id);
    }

    /** 申请退款 */
    @PostMapping("/{id}/refund")
    public OrderDto refund(@PathVariable Long id, @RequestBody Map<String, String> body) {
        return orderService.requestRefund(currentUserId(), id, body.get("reason"));
    }

    private Long currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof Long id)) {
            throw new AppException("UNAUTHORIZED", "请先登录");
        }
        return id;
    }
}
