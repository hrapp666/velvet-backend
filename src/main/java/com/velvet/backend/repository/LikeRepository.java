package com.velvet.backend.repository;

import com.velvet.backend.entity.Like;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface LikeRepository extends JpaRepository<Like, Long> {

    Optional<Like> findByUserIdAndMomentId(Long userId, Long momentId);

    boolean existsByUserIdAndMomentId(Long userId, Long momentId);

    void deleteByUserIdAndMomentId(Long userId, Long momentId);

    long countByMomentId(Long momentId);

    /**
     * 取用户最近 N 个 like 的 momentId（按 created_at 倒序）
     * 用于推荐算法收集 tag 偏好。
     */
    @Query("SELECT l.momentId FROM Like l WHERE l.userId = :userId ORDER BY l.createdAt DESC")
    List<Long> findRecentMomentIdsByUserId(@Param("userId") Long userId, Pageable pageable);

    /** 注销账号用：清理 userId 的全部 like 记录 */
    @Modifying
    @Query("DELETE FROM Like l WHERE l.userId = :uid")
    int deleteAllByUserId(@Param("uid") Long uid);
}
