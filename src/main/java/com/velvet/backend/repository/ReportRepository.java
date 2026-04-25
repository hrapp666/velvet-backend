package com.velvet.backend.repository;

import com.velvet.backend.entity.Report;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ReportRepository extends JpaRepository<Report, Long> {
    List<Report> findByStatusOrderByCreatedAtDesc(String status);
    long countByStatus(String status);
    boolean existsByReporterIdAndTargetTypeAndTargetId(
            Long reporterId, String targetType, Long targetId);
}
