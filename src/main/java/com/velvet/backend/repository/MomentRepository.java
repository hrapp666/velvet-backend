package com.velvet.backend.repository;

import com.velvet.backend.entity.Moment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface MomentRepository extends JpaRepository<Moment, Long> {

    Page<Moment> findByStatusOrderByCreatedAtDesc(String status, Pageable pageable);

    Page<Moment> findByUserIdAndStatusOrderByCreatedAtDesc(
            Long userId, String status, Pageable pageable
    );

    /** 作者自己看自己的：含 PENDING_REVIEW / PUBLISHED / REJECTED，排除 DELETED */
    @Query("SELECT m FROM Moment m WHERE m.userId = :userId AND m.status <> 'DELETED' " +
           "ORDER BY m.createdAt DESC")
    Page<Moment> findByUserIdExcludingDeleted(@Param("userId") Long userId, Pageable pageable);

    /** 后台审核队列 */
    Page<Moment> findByStatusOrderByCreatedAtAsc(String status, Pageable pageable);

    @Query("SELECT m FROM Moment m WHERE m.status = 'PUBLISHED' " +
           "AND m.userId IN (SELECT f.followeeId FROM Follow f WHERE f.followerId = :userId) " +
           "ORDER BY m.createdAt DESC")
    Page<Moment> findFeedForUser(@Param("userId") Long userId, Pageable pageable);

    /** 全文搜索 — title 或 content 包含关键词 */
    @Query("SELECT m FROM Moment m WHERE m.status = 'PUBLISHED' " +
           "AND (LOWER(m.title) LIKE LOWER(CONCAT('%', :q, '%')) " +
           "  OR LOWER(m.content) LIKE LOWER(CONCAT('%', :q, '%'))) " +
           "ORDER BY m.createdAt DESC")
    Page<Moment> searchByQuery(@Param("q") String q, Pageable pageable);

    /**
     * 同城 / 附近预过滤 — bounding box 内的已发布动态
     * <p>
     * 设计：先用 lat/lng 矩形预过滤（走索引），最多取 200 条进内存，
     * Service 层再做精确的 Haversine 距离计算 + 排序 + 截断分页。
     * <p>
     * 这种"box prefilter + 内存精算"模式避免引入 PostGIS 扩展，
     * 在 C2C 同城场景（半径 ≤ 50km）下性能足够。
     */
    @Query(value = """
        SELECT * FROM moments
        WHERE status = 'PUBLISHED'
          AND latitude  IS NOT NULL
          AND longitude IS NOT NULL
          AND latitude  BETWEEN :latMin AND :latMax
          AND longitude BETWEEN :lngMin AND :lngMax
        ORDER BY created_at DESC
        LIMIT 200
        """, nativeQuery = true)
    java.util.List<Moment> findInBoundingBox(
            @Param("latMin") double latMin,
            @Param("latMax") double latMax,
            @Param("lngMin") double lngMin,
            @Param("lngMax") double lngMax
    );

    /**
     * 推荐 candidate pool — 最近 N 条 PUBLISHED moment（按时间倒序）。
     * Service 层负责再做"排除自己 + 排除已 like"的内存过滤。
     * <p>
     * 200 条 pool 在 v0 版本足够 (jaccard 算法 O(N) 内存计算 < 5ms)。
     */
    @Query(value = """
        SELECT * FROM moments
        WHERE status = 'PUBLISHED'
        ORDER BY created_at DESC
        LIMIT :poolSize
        """, nativeQuery = true)
    java.util.List<Moment> findRecentPublishedForRecommend(@Param("poolSize") int poolSize);

    /** 按 ids 批量取已发布 moments — 用于读取用户已 like 的 moment tags */
    @Query("SELECT m FROM Moment m WHERE m.id IN :ids AND m.status = 'PUBLISHED'")
    java.util.List<Moment> findAllByIdInAndPublished(@Param("ids") java.util.Collection<Long> ids);
}
