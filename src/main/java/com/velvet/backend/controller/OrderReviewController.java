package com.velvet.backend.controller;

import com.velvet.backend.entity.Merchant;
import com.velvet.backend.entity.Order;
import com.velvet.backend.entity.OrderReview;
import com.velvet.backend.entity.User;
import com.velvet.backend.exception.AppException;
import com.velvet.backend.repository.MerchantRepository;
import com.velvet.backend.repository.OrderRepository;
import com.velvet.backend.repository.OrderReviewRepository;
import com.velvet.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/reviews")
@RequiredArgsConstructor
public class OrderReviewController {

    private final OrderReviewRepository reviewRepo;
    private final OrderRepository orderRepo;
    private final UserRepository userRepo;
    private final MerchantRepository merchantRepo;

    public record ReviewDto(
            Long id,
            Long orderId,
            String reviewerRole,
            Long reviewerId,
            String reviewerNickname,
            Short rating,
            String content,
            LocalDateTime createdAt
    ) {}

    public record CreateReviewRequest(
            Long orderId,
            Short rating,
            String content
    ) {}

    /** 提交评价 */
    @PostMapping
    public ReviewDto create(@RequestBody CreateReviewRequest req) {
        Long userId = currentUserId();
        if (req.orderId() == null || req.rating() == null) {
            throw new AppException("INVALID_REQUEST", "缺少必填字段");
        }
        if (req.rating() < 1 || req.rating() > 5) {
            throw new AppException("INVALID_REQUEST", "评分 1-5 星");
        }
        Order order = orderRepo.findById(req.orderId())
                .orElseThrow(() -> new AppException("ORDER_NOT_FOUND", "订单不存在"));
        if (!"CONFIRMED".equals(order.getStatus())) {
            throw new AppException("INVALID_STATE", "只有已完成订单可评价");
        }

        // 判断角色
        String role;
        if (order.getBuyerId().equals(userId)) role = "BUYER";
        else if (order.getSellerId().equals(userId)) role = "SELLER";
        else throw new AppException("FORBIDDEN", "无权评价此订单");

        // 防重复
        if (reviewRepo.findByOrderIdAndReviewerRole(order.getId(), role).isPresent()) {
            throw new AppException("DUPLICATE", "已评价过");
        }

        OrderReview review = reviewRepo.save(OrderReview.builder()
                .orderId(order.getId())
                .reviewerRole(role)
                .reviewerId(userId)
                .rating(req.rating())
                .content(req.content())
                .build());

        // 如果是买家评卖家 → 聚合更新商家评分
        if ("BUYER".equals(role)) {
            updateMerchantRating(order.getSellerId());
        }
        return toDto(review);
    }

    private void updateMerchantRating(Long sellerId) {
        try {
            Double avg = reviewRepo.findAverageRatingForUser(sellerId);
            if (avg == null) return;
            Merchant m = merchantRepo.findByUserId(sellerId).orElse(null);
            if (m == null) return;
            m.setRating(BigDecimal.valueOf(avg).setScale(2, RoundingMode.HALF_UP));
            // 销量 = 已完成 (CONFIRMED) 订单数
            var completedOrders = orderRepo.countBySellerIdAndStatus(sellerId, "CONFIRMED");
            m.setSalesCount(Math.toIntExact(completedOrders));
            merchantRepo.save(m);
            log.info("[review] merchant {} rating={} sales={}", m.getId(), avg, completedOrders);
        } catch (Exception e) {
            log.warn("[review] updateMerchantRating failed for seller {}", sellerId, e);
        }
    }

    /** 某订单的所有评价 */
    @GetMapping("/order/{orderId}")
    public List<ReviewDto> listForOrder(@PathVariable Long orderId) {
        return reviewRepo.findByOrderId(orderId).stream().map(this::toDto).toList();
    }

    /** 某用户被评价的列表 (公开) */
    @GetMapping("/public/user/{userId}")
    public Map<String, Object> userReviews(@PathVariable Long userId) {
        var reviews = reviewRepo.findReviewsForUser(userId);
        Double avg = reviewRepo.findAverageRatingForUser(userId);
        return Map.of(
                "averageRating", avg != null ? avg : 0.0,
                "totalCount", reviews.size(),
                "reviews", reviews.stream().limit(20).map(this::toDto).toList()
        );
    }

    private ReviewDto toDto(OrderReview r) {
        User reviewer = userRepo.findById(r.getReviewerId()).orElse(null);
        return new ReviewDto(
                r.getId(),
                r.getOrderId(),
                r.getReviewerRole(),
                r.getReviewerId(),
                reviewer != null ? reviewer.getNickname() : "(已注销)",
                r.getRating(),
                r.getContent(),
                r.getCreatedAt()
        );
    }

    private Long currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof Long id)) {
            throw new AppException("UNAUTHORIZED", "请先登录");
        }
        return id;
    }
}
