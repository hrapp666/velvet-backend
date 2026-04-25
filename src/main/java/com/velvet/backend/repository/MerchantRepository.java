package com.velvet.backend.repository;

import com.velvet.backend.entity.Merchant;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface MerchantRepository extends JpaRepository<Merchant, Long> {

    Optional<Merchant> findByUserId(Long userId);

    Page<Merchant> findByStatus(String status, Pageable pageable);

    List<Merchant> findByStatusOrderByCreatedAtDesc(String status);

    boolean existsByUserId(Long userId);
}
