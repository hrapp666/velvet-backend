package com.velvet.backend.repository;

import com.velvet.backend.entity.Withdrawal;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface WithdrawalRepository extends JpaRepository<Withdrawal, Long> {
    Page<Withdrawal> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);
    Page<Withdrawal> findByStatusOrderByCreatedAtDesc(String status, Pageable pageable);
    List<Withdrawal> findByStatusOrderByCreatedAtDesc(String status);
}
