package com.velvet.backend.repository;

import com.velvet.backend.entity.Comment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CommentRepository extends JpaRepository<Comment, Long> {

    Page<Comment> findByMomentIdAndParentIdIsNullOrderByCreatedAtDesc(
            Long momentId, Pageable pageable);

    List<Comment> findByParentIdOrderByCreatedAtAsc(Long parentId);

    long countByMomentId(Long momentId);
}
