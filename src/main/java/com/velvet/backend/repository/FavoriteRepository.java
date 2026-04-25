package com.velvet.backend.repository;

import com.velvet.backend.entity.Favorite;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface FavoriteRepository extends JpaRepository<Favorite, Long> {

    boolean existsByUserIdAndMomentId(Long userId, Long momentId);

    void deleteByUserIdAndMomentId(Long userId, Long momentId);

    Page<Favorite> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    long countByMomentId(Long momentId);

    /**
     * 取用户最近 N 个 favorite 的 momentId（按 created_at 倒序）
     * 用于推荐算法收集 tag 偏好。
     */
    @Query("SELECT f.momentId FROM Favorite f WHERE f.userId = :userId ORDER BY f.createdAt DESC")
    List<Long> findRecentMomentIdsByUserId(@Param("userId") Long userId, Pageable pageable);

    /** 注销账号用：清理 userId 的全部 favorite 记录 */
    @Modifying
    @Query("DELETE FROM Favorite f WHERE f.userId = :uid")
    int deleteAllByUserId(@Param("uid") Long uid);
}
