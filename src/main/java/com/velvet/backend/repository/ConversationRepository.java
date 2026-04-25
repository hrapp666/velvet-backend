package com.velvet.backend.repository;

import com.velvet.backend.entity.Conversation;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ConversationRepository extends JpaRepository<Conversation, Long> {

    /**
     * 查找两人之间（可选关联到某 moment）的会话
     * 始终保证 a &lt; b
     */
    @Query("""
            SELECT c FROM Conversation c
            WHERE c.userAId = :a AND c.userBId = :b
              AND ((:momentId IS NULL AND c.momentId IS NULL)
                   OR c.momentId = :momentId)
            """)
    Optional<Conversation> findPair(
            @Param("a") Long a,
            @Param("b") Long b,
            @Param("momentId") Long momentId);

    /**
     * 列出某用户的所有会话（按最后消息时间倒序）
     */
    @Query("""
            SELECT c FROM Conversation c
            WHERE c.userAId = :userId OR c.userBId = :userId
            ORDER BY c.lastMessageAt DESC NULLS LAST, c.updatedAt DESC
            """)
    Page<Conversation> findAllByUser(@Param("userId") Long userId, Pageable pageable);
}
