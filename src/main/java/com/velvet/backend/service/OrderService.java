package com.velvet.backend.service;

import com.velvet.backend.entity.Moment;
import com.velvet.backend.entity.Order;
import com.velvet.backend.entity.User;
import com.velvet.backend.event.NotificationEvent;
import com.velvet.backend.event.OrderEvent;
import com.velvet.backend.exception.AppException;
import com.velvet.backend.repository.MomentRepository;
import com.velvet.backend.repository.OrderRepository;
import com.velvet.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 订单服务 — Velvet C2C 交易闭环
 *
 * <p>状态机：
 * <ul>
 *   <li>PENDING：买家下单，等待付款</li>
 *   <li>PAID：买家已付款，等卖家发货</li>
 *   <li>SHIPPED：卖家已发货，等买家收货</li>
 *   <li>RECEIVED：买家已确认收货（自动或手动）</li>
 *   <li>CONFIRMED：交易完成，可评价</li>
 *   <li>CANCELED：买家取消（仅 PENDING 可）</li>
 *   <li>REFUND_REQ：买家申请退款</li>
 *   <li>REFUNDED：已退款</li>
 *   <li>DISPUTE：争议中</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderRepository orderRepo;
    private final MomentRepository momentRepo;
    private final UserRepository userRepo;
    private final NotificationService notificationService;
    private final WalletService walletService;
    private final EventPublisher eventPublisher;

    // ==========================================================================
    // DTO
    // ==========================================================================
    public record OrderDto(
            Long id,
            Long momentId,
            Long buyerId,
            String buyerNickname,
            Long sellerId,
            String sellerNickname,
            Long priceCents,
            Long commissionCents,
            String titleSnapshot,
            String coverSnapshot,
            String status,
            String paymentMethod,
            LocalDateTime paidAt,
            LocalDateTime shippedAt,
            String trackingNo,
            LocalDateTime receivedAt,
            LocalDateTime confirmedAt,
            String buyerNote,
            String sellerNote,
            String shippingName,
            String shippingPhone,
            String shippingAddress,
            LocalDateTime createdAt
    ) {}

    public record CreateOrderRequest(
            Long momentId,
            String buyerNote,
            String shippingName,
            String shippingPhone,
            String shippingAddress
    ) {}

    public record PayRequest(
            String paymentMethod  // WECHAT / ALIPAY / MOCK
    ) {}

    public record ShipRequest(
            String trackingNo,
            String sellerNote
    ) {}

    // ==========================================================================
    // 操作
    // ==========================================================================

    /**
     * 创建订单（买家下单 → PENDING）
     */
    @Transactional
    public OrderDto create(Long buyerId, CreateOrderRequest req) {
        if (req.momentId() == null) {
            throw new AppException("INVALID_REQUEST", "缺少 momentId");
        }
        Moment moment = momentRepo.findById(req.momentId())
                .orElseThrow(() -> new AppException("MOMENT_NOT_FOUND", "动态不存在"));
        if (!moment.getHasItem() || moment.getItemPriceCents() == null
                || moment.getItemPriceCents() <= 0) {
            throw new AppException("NOT_FOR_SALE", "此动态未挂售");
        }
        if (moment.getUserId().equals(buyerId)) {
            throw new AppException("INVALID_REQUEST", "不能购买自己的物品");
        }
        // 防止重复下单：同 moment + 同 buyer + 未完结的订单已存在
        var existing = orderRepo.findByMomentIdAndBuyerIdAndStatusIn(
                moment.getId(), buyerId,
                List.of("PENDING", "PAID", "SHIPPED", "RECEIVED"));
        if (!existing.isEmpty()) {
            throw new AppException("DUPLICATE_ORDER", "已有未完结的订单");
        }

        // 校验收货信息
        if (req.shippingName() == null || req.shippingName().isBlank()) {
            throw new AppException("INVALID_REQUEST", "请填写收货人姓名");
        }
        if (req.shippingPhone() == null || !req.shippingPhone().matches("^1\\d{10}$")) {
            throw new AppException("INVALID_REQUEST", "手机号格式不正确");
        }
        if (req.shippingAddress() == null || req.shippingAddress().isBlank()) {
            throw new AppException("INVALID_REQUEST", "请填写收货地址");
        }

        Order order = orderRepo.save(Order.builder()
                .momentId(moment.getId())
                .buyerId(buyerId)
                .sellerId(moment.getUserId())
                .priceCents(moment.getItemPriceCents().longValue())
                .titleSnapshot(moment.getTitle() != null
                        ? moment.getTitle() : moment.getContent())
                .coverSnapshot(moment.getMediaUrls() != null
                        && !moment.getMediaUrls().isEmpty()
                        ? moment.getMediaUrls().get(0) : null)
                .status("PENDING")
                .buyerNote(req.buyerNote())
                .shippingName(req.shippingName().trim())
                .shippingPhone(req.shippingPhone().trim())
                .shippingAddress(req.shippingAddress().trim())
                .build());

        // 发布订单创建事件
        eventPublisher.publishOrderEvent(
                OrderEvent.of("CREATED", order.getId(), buyerId, moment.getUserId(), order.getPriceCents()));

        // 通知卖家（Kafka 优先，失败则同步 fallback）
        User buyer = userRepo.findById(buyerId).orElse(null);
        String buyerName = buyer != null ? buyer.getNickname() : "某人";
        String notifTitle = buyerName + " 拍下了你的物件";
        String notifContent = moment.getTitle() != null ? moment.getTitle() : "";
        boolean published = eventPublisher.publishNotification(
                new NotificationEvent(moment.getUserId(), "ORDER", notifTitle, notifContent,
                        buyerId, "ORDER", order.getId()));
        if (!published) {
            try {
                notificationService.create(moment.getUserId(), "ORDER", notifTitle, notifContent,
                        buyerId, "ORDER", order.getId());
            } catch (Exception e) {
                log.warn("[notify] failed orderId={}", order.getId(), e);
            }
        }

        return toDto(order);
    }

    /**
     * 买家付款 (PENDING → PAID)
     * MVP: MOCK 支付直接成功；后续接微信/支付宝
     */
    @Transactional
    public OrderDto pay(Long userId, Long orderId, PayRequest req) {
        Order order = mustOwnAsBuyer(orderId, userId);
        if (!"PENDING".equals(order.getStatus())) {
            throw new AppException("INVALID_STATE",
                    "订单状态不允许付款：" + order.getStatus());
        }
        String method = req.paymentMethod() != null ? req.paymentMethod() : "MOCK";
        if (!Set.of("WECHAT", "ALIPAY", "MOCK").contains(method)) {
            throw new AppException("INVALID_REQUEST", "不支持的支付方式");
        }
        order.setStatus("PAID");
        order.setPaymentMethod(method);
        order.setPaymentId("mock_" + UUID.randomUUID().toString().substring(0, 16));
        order.setPaidAt(LocalDateTime.now());
        Order saved = orderRepo.save(order);

        // 发布订单支付事件
        eventPublisher.publishOrderEvent(
                OrderEvent.of("PAID", saved.getId(), userId, order.getSellerId(), order.getPriceCents()));

        // 通知卖家（Kafka 优先，失败则同步 fallback）
        boolean paidNotif = eventPublisher.publishNotification(
                new NotificationEvent(order.getSellerId(), "ORDER", "买家已付款 · 请尽快发货",
                        order.getTitleSnapshot(), userId, "ORDER", order.getId()));
        if (!paidNotif) {
            try {
                notificationService.create(order.getSellerId(), "ORDER", "买家已付款 · 请尽快发货",
                        order.getTitleSnapshot(), userId, "ORDER", order.getId());
            } catch (Exception e) {
                log.warn("[notify] failed orderId={}", order.getId(), e);
            }
        }

        return toDto(saved);
    }

    /**
     * 卖家发货 (PAID → SHIPPED)
     */
    @Transactional
    public OrderDto ship(Long userId, Long orderId, ShipRequest req) {
        Order order = mustOwnAsSeller(orderId, userId);
        if (!"PAID".equals(order.getStatus())) {
            throw new AppException("INVALID_STATE",
                    "订单状态不允许发货：" + order.getStatus());
        }
        order.setStatus("SHIPPED");
        order.setShippedAt(LocalDateTime.now());
        order.setTrackingNo(req.trackingNo());
        order.setSellerNote(req.sellerNote());
        Order saved = orderRepo.save(order);

        // 发布订单发货事件
        eventPublisher.publishOrderEvent(
                OrderEvent.of("SHIPPED", saved.getId(), order.getBuyerId(), userId, order.getPriceCents()));

        // 通知买家（Kafka 优先，失败则同步 fallback）
        boolean shippedNotif = eventPublisher.publishNotification(
                new NotificationEvent(order.getBuyerId(), "ORDER", "卖家已发货",
                        order.getTitleSnapshot(), userId, "ORDER", order.getId()));
        if (!shippedNotif) {
            try {
                notificationService.create(order.getBuyerId(), "ORDER", "卖家已发货",
                        order.getTitleSnapshot(), userId, "ORDER", order.getId());
            } catch (Exception e) {
                log.warn("[notify] failed orderId={}", order.getId(), e);
            }
        }

        return toDto(saved);
    }

    /**
     * 买家确认收货 (SHIPPED → CONFIRMED)
     */
    @Transactional
    public OrderDto confirm(Long userId, Long orderId) {
        Order order = mustOwnAsBuyer(orderId, userId);
        if (!"SHIPPED".equals(order.getStatus()) && !"RECEIVED".equals(order.getStatus())) {
            throw new AppException("INVALID_STATE",
                    "订单状态不允许确认：" + order.getStatus());
        }
        order.setStatus("CONFIRMED");
        order.setConfirmedAt(LocalDateTime.now());
        if (order.getReceivedAt() == null) {
            order.setReceivedAt(LocalDateTime.now());
        }
        Order saved = orderRepo.save(order);

        // 结算到卖家钱包
        try {
            walletService.settleOrder(saved);
        } catch (Exception e) {
            log.error("[WALLET] settle FAILED for order {}", saved.getId(), e);
        }

        // 发布订单确认事件
        eventPublisher.publishOrderEvent(
                OrderEvent.of("CONFIRMED", saved.getId(), userId, order.getSellerId(), order.getPriceCents()));

        // 通知卖家（Kafka 优先，失败则同步 fallback）
        boolean confirmedNotif = eventPublisher.publishNotification(
                new NotificationEvent(order.getSellerId(), "ORDER", "买家已确认收货 · 交易完成",
                        order.getTitleSnapshot(), userId, "ORDER", order.getId()));
        if (!confirmedNotif) {
            try {
                notificationService.create(order.getSellerId(), "ORDER", "买家已确认收货 · 交易完成",
                        order.getTitleSnapshot(), userId, "ORDER", order.getId());
            } catch (Exception e) {
                log.warn("[notify] failed orderId={}", order.getId(), e);
            }
        }

        return toDto(saved);
    }

    /**
     * 取消订单 (PENDING → CANCELED)
     */
    @Transactional
    public OrderDto cancel(Long userId, Long orderId) {
        Order order = orderRepo.findById(orderId)
                .orElseThrow(() -> new AppException("ORDER_NOT_FOUND", "订单不存在"));
        // 买卖双方都能取消未付款订单
        if (!order.getBuyerId().equals(userId) && !order.getSellerId().equals(userId)) {
            throw new AppException("FORBIDDEN", "无权操作此订单");
        }
        if (!"PENDING".equals(order.getStatus())) {
            throw new AppException("INVALID_STATE",
                    "只能取消待付款订单：" + order.getStatus());
        }
        order.setStatus("CANCELED");
        order.setCanceledAt(LocalDateTime.now());
        Order saved = orderRepo.save(order);

        // 发布订单取消事件
        eventPublisher.publishOrderEvent(
                OrderEvent.of("CANCELLED", saved.getId(), saved.getBuyerId(),
                        saved.getSellerId(), saved.getPriceCents()));

        return toDto(saved);
    }

    /**
     * 申请退款 (PAID/SHIPPED → REFUND_REQ)
     */
    @Transactional
    public OrderDto requestRefund(Long userId, Long orderId, String reason) {
        Order order = mustOwnAsBuyer(orderId, userId);
        if (!Set.of("PAID", "SHIPPED", "RECEIVED").contains(order.getStatus())) {
            throw new AppException("INVALID_STATE",
                    "订单状态不允许退款：" + order.getStatus());
        }
        order.setStatus("REFUND_REQ");
        order.setRefundReason(reason);
        Order saved = orderRepo.save(order);

        // 通知卖家退款申请（Kafka 优先，失败则同步 fallback）
        String refundContent = reason != null ? reason : "";
        boolean refundNotif = eventPublisher.publishNotification(
                new NotificationEvent(order.getSellerId(), "ORDER", "买家申请退款",
                        refundContent, userId, "ORDER", order.getId()));
        if (!refundNotif) {
            try {
                notificationService.create(order.getSellerId(), "ORDER", "买家申请退款",
                        refundContent, userId, "ORDER", order.getId());
            } catch (Exception e) {
                log.warn("[notify] failed orderId={}", order.getId(), e);
            }
        }

        return toDto(saved);
    }

    // ==========================================================================
    // 查询
    // ==========================================================================
    @Transactional(readOnly = true)
    public OrderDto getById(Long userId, Long orderId) {
        Order order = orderRepo.findById(orderId)
                .orElseThrow(() -> new AppException("ORDER_NOT_FOUND", "订单不存在"));
        if (!order.getBuyerId().equals(userId) && !order.getSellerId().equals(userId)) {
            throw new AppException("FORBIDDEN", "无权查看此订单");
        }
        return toDto(order);
    }

    @Transactional(readOnly = true)
    public Page<OrderDto> listAsBuyer(Long buyerId, int page, int size) {
        Page<Order> orders = orderRepo.findByBuyerIdOrderByCreatedAtDesc(
                buyerId, PageRequest.of(page, Math.min(size, 50)));
        // N+1 fix: 批量 fetch 所有 buyerId + sellerId 对应的 users
        Map<Long, User> userMap = batchFetchOrderUsers(orders.getContent());
        return orders.map(o -> toDto(o, userMap));
    }

    @Transactional(readOnly = true)
    public Page<OrderDto> listAsSeller(Long sellerId, int page, int size) {
        Page<Order> orders = orderRepo.findBySellerIdOrderByCreatedAtDesc(
                sellerId, PageRequest.of(page, Math.min(size, 50)));
        // N+1 fix: 批量 fetch 所有 buyerId + sellerId 对应的 users
        Map<Long, User> userMap = batchFetchOrderUsers(orders.getContent());
        return orders.map(o -> toDto(o, userMap));
    }

    // ==========================================================================
    // helpers
    // ==========================================================================
    private Order mustOwnAsBuyer(Long orderId, Long userId) {
        Order order = orderRepo.findById(orderId)
                .orElseThrow(() -> new AppException("ORDER_NOT_FOUND", "订单不存在"));
        if (!order.getBuyerId().equals(userId)) {
            throw new AppException("FORBIDDEN", "只有买家可以操作");
        }
        return order;
    }

    private Order mustOwnAsSeller(Long orderId, Long userId) {
        Order order = orderRepo.findById(orderId)
                .orElseThrow(() -> new AppException("ORDER_NOT_FOUND", "订单不存在"));
        if (!order.getSellerId().equals(userId)) {
            throw new AppException("FORBIDDEN", "只有卖家可以操作");
        }
        return order;
    }

    /**
     * 批量 fetch 一组 orders 对应的 buyer + seller users (N+1 fix)
     * 用 findAllById 一条 SQL 拿全
     */
    private Map<Long, User> batchFetchOrderUsers(List<Order> orders) {
        if (orders.isEmpty()) return Map.of();
        Set<Long> userIds = new HashSet<>();
        for (Order o : orders) {
            if (o.getBuyerId() != null) userIds.add(o.getBuyerId());
            if (o.getSellerId() != null) userIds.add(o.getSellerId());
        }
        Map<Long, User> map = new HashMap<>();
        for (User u : userRepo.findAllById(userIds)) {
            map.put(u.getId(), u);
        }
        return map;
    }

    /** 批量版本：从预取的 userMap 取 buyer/seller，不再逐条查库 */
    private OrderDto toDto(Order o, Map<Long, User> userMap) {
        User buyer = userMap.get(o.getBuyerId());
        User seller = userMap.get(o.getSellerId());
        return new OrderDto(
                o.getId(),
                o.getMomentId(),
                o.getBuyerId(),
                buyer != null ? buyer.getNickname() : "(已注销)",
                o.getSellerId(),
                seller != null ? seller.getNickname() : "(已注销)",
                o.getPriceCents(),
                o.getCommissionCents() != null ? o.getCommissionCents() : 0L,
                o.getTitleSnapshot(),
                o.getCoverSnapshot(),
                o.getStatus(),
                o.getPaymentMethod(),
                o.getPaidAt(),
                o.getShippedAt(),
                o.getTrackingNo(),
                o.getReceivedAt(),
                o.getConfirmedAt(),
                o.getBuyerNote(),
                o.getSellerNote(),
                o.getShippingName(),
                o.getShippingPhone(),
                o.getShippingAddress(),
                o.getCreatedAt()
        );
    }

    /** 单条版本：create/pay/ship 等单条场景使用，保留原签名 */
    private OrderDto toDto(Order o) {
        User buyer = userRepo.findById(o.getBuyerId()).orElse(null);
        User seller = userRepo.findById(o.getSellerId()).orElse(null);
        return new OrderDto(
                o.getId(),
                o.getMomentId(),
                o.getBuyerId(),
                buyer != null ? buyer.getNickname() : "(已注销)",
                o.getSellerId(),
                seller != null ? seller.getNickname() : "(已注销)",
                o.getPriceCents(),
                o.getCommissionCents() != null ? o.getCommissionCents() : 0L,
                o.getTitleSnapshot(),
                o.getCoverSnapshot(),
                o.getStatus(),
                o.getPaymentMethod(),
                o.getPaidAt(),
                o.getShippedAt(),
                o.getTrackingNo(),
                o.getReceivedAt(),
                o.getConfirmedAt(),
                o.getBuyerNote(),
                o.getSellerNote(),
                o.getShippingName(),
                o.getShippingPhone(),
                o.getShippingAddress(),
                o.getCreatedAt()
        );
    }
}
