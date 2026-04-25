package com.velvet.backend.repository;

import com.velvet.backend.entity.Order;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface OrderRepository extends JpaRepository<Order, Long> {

    List<Order> findByStatusAndCreatedAtBefore(String status, LocalDateTime cutoff);

    List<Order> findByStatusAndShippedAtBefore(String status, LocalDateTime cutoff);

    Page<Order> findByBuyerIdOrderByCreatedAtDesc(Long buyerId, Pageable pageable);

    Page<Order> findBySellerIdOrderByCreatedAtDesc(Long sellerId, Pageable pageable);

    long countByBuyerIdAndStatus(Long buyerId, String status);

    long countBySellerIdAndStatus(Long sellerId, String status);

    List<Order> findByMomentIdAndBuyerIdAndStatusIn(
            Long momentId, Long buyerId, List<String> statuses);
}
