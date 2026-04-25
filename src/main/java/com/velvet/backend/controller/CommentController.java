package com.velvet.backend.controller;

import com.velvet.backend.exception.AppException;
import com.velvet.backend.service.CommentService;
import com.velvet.backend.service.CommentService.CommentDto;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/comments")
@RequiredArgsConstructor
public class CommentController {

    private final CommentService commentService;

    @GetMapping("/public/moment/{momentId}")
    public Page<CommentDto> list(
            @PathVariable Long momentId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return commentService.list(momentId, page, size);
    }

    @GetMapping("/public/{parentId}/replies")
    public List<CommentDto> listReplies(@PathVariable Long parentId) {
        return commentService.listReplies(parentId);
    }

    @PostMapping
    public CommentDto create(@RequestBody Map<String, Object> body) {
        Long momentId = ((Number) body.get("momentId")).longValue();
        String content = (String) body.get("content");
        Long parentId = body.get("parentId") != null
                ? ((Number) body.get("parentId")).longValue()
                : null;
        return commentService.create(currentUserId(), momentId, content, parentId);
    }

    /** 评论点赞（toggle） */
    @PostMapping("/{id}/like")
    public Map<String, Object> toggleLike(@PathVariable Long id) {
        boolean liked = commentService.toggleCommentLike(currentUserId(), id);
        return Map.of("liked", liked);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        commentService.delete(id, currentUserId());
    }

    private Long currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof Long id)) {
            throw new AppException("UNAUTHORIZED", "请先登录");
        }
        return id;
    }
}
