package com.velvet.backend.repository;

import com.velvet.backend.entity.Follow;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface FollowRepository extends JpaRepository<Follow, Long> {

    boolean existsByFollowerIdAndFolloweeId(Long followerId, Long followeeId);

    void deleteByFollowerIdAndFolloweeId(Long followerId, Long followeeId);

    Page<Follow> findByFollowerIdOrderByCreatedAtDesc(Long followerId, Pageable pageable);

    Page<Follow> findByFolloweeIdOrderByCreatedAtDesc(Long followeeId, Pageable pageable);

    long countByFollowerId(Long followerId);

    long countByFolloweeId(Long followeeId);

    /** 注销账号用：清理 userId 相关的全部 follow 记录（作为 follower 或 followee）*/
    @Modifying
    @Query("DELETE FROM Follow f WHERE f.followerId = :uid OR f.followeeId = :uid")
    int deleteAllByFollowerIdOrFolloweeId(@Param("uid") Long uid);
}
