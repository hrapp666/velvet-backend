package com.velvet.backend.repository;

import com.velvet.backend.entity.WalletEntry;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface WalletEntryRepository extends JpaRepository<WalletEntry, Long> {
    Page<WalletEntry> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);
}
