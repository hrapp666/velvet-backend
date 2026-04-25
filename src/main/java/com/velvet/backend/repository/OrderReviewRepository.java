package com.velvet.backend.repository;

import com.velvet.backend.entity.OrderReview;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface OrderReviewRepository extends JpaRepository<OrderReview, Long> {

    Optional<OrderReview> findByOrderIdAndReviewerRole(Long orderId, String reviewerRole);

    List<OrderReview> findByOrderId(Long orderId);

    /** 用户被评价的所有 review (作为卖家被买家评 + 作为买家被卖家评) */
    @Query("""
            SELECT r FROM OrderReview r
            JOIN com.velvet.backend.entity.Order o ON r.orderId = o.id
            WHERE (o.sellerId = :userId AND r.reviewerRole = 'BUYER')
               OR (o.buyerId = :userId AND r.reviewerRole = 'SELLER')
            ORDER BY r.createdAt DESC
            """)
    List<OrderReview> findReviewsForUser(@Param("userId") Long userId);

    /** 用户的平均评分 */
    @Query("""
            SELECT AVG(r.rating) FROM OrderReview r
            JOIN com.velvet.backend.entity.Order o ON r.orderId = o.id
            WHERE (o.sellerId = :userId AND r.reviewerRole = 'BUYER')
               OR (o.buyerId = :userId AND r.reviewerRole = 'SELLER')
            """)
    Double findAverageRatingForUser(@Param("userId") Long userId);
}
