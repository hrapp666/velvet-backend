package com.velvet.backend.repository;

import com.velvet.backend.entity.Block;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface BlockRepository extends JpaRepository<Block, Long> {

    Optional<Block> findByBlockerIdAndBlockedId(Long blockerId, Long blockedId);

    boolean existsByBlockerIdAndBlockedId(Long blockerId, Long blockedId);

    Page<Block> findByBlockerIdOrderByCreatedAtDesc(Long blockerId, Pageable pageable);

    long deleteByBlockerIdAndBlockedId(Long blockerId, Long blockedId);

    @Query("SELECT b.blockedId FROM Block b WHERE b.blockerId = :uid")
    List<Long> findBlockedIds(@Param("uid") Long uid);

    @Query("SELECT b.blockerId FROM Block b WHERE b.blockedId = :uid")
    List<Long> findBlockerIds(@Param("uid") Long uid);

    /** 双向：{uid 拉黑的} ∪ {拉黑 uid 的} — feed/chat/moment 过滤用 */
    @Query("""
        SELECT CASE WHEN b.blockerId = :uid THEN b.blockedId ELSE b.blockerId END
        FROM Block b
        WHERE b.blockerId = :uid OR b.blockedId = :uid
        """)
    List<Long> findMutualBlockedIds(@Param("uid") Long uid);

    @Query("""
        SELECT COUNT(b) > 0 FROM Block b
        WHERE (b.blockerId = :a AND b.blockedId = :b)
           OR (b.blockerId = :b AND b.blockedId = :a)
        """)
    boolean existsBetween(@Param("a") Long a, @Param("b") Long b);

    /** 批量删除 userId 相关的全部拉黑关系（注销账号时用）*/
    @Query("DELETE FROM Block b WHERE b.blockerId = :uid OR b.blockedId = :uid")
    @org.springframework.data.jpa.repository.Modifying
    int deleteAllRelatedToUser(@Param("uid") Long uid);
}
