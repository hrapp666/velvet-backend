package com.velvet.backend.repository;

import com.velvet.backend.entity.Message;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface MessageRepository extends JpaRepository<Message, Long> {

    Page<Message> findByConversationIdOrderByCreatedAtDesc(
            Long conversationId, Pageable pageable);

    long countByConversationIdAndIsReadFalseAndSenderIdNot(
            Long conversationId, Long excludeSenderId);
}
