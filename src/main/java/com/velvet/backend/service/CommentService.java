package com.velvet.backend.service;

import com.velvet.backend.entity.Comment;
import com.velvet.backend.entity.CommentLike;
import com.velvet.backend.entity.Moment;
import com.velvet.backend.entity.User;
import com.velvet.backend.exception.AppException;
import com.velvet.backend.repository.CommentLikeRepository;
import com.velvet.backend.repository.CommentRepository;
import com.velvet.backend.repository.MomentRepository;
import com.velvet.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.HtmlUtils;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CommentService {

    private final CommentRepository commentRepo;
    private final CommentLikeRepository commentLikeRepo;
    private final MomentRepository momentRepo;
    private final UserRepository userRepo;
    private final NotificationService notifService;

    public record CommentDto(
            Long id,
            Long momentId,
            Long userId,
            String userNickname,
            String userAvatarUrl,
            Long parentId,
            String content,
            Integer likeCount,
            LocalDateTime createdAt
    ) {}

    @Transactional
    public CommentDto create(Long userId, Long momentId, String content, Long parentId) {
        if (content == null || content.isBlank()) {
            throw new AppException("INVALID_REQUEST", "评论内容不能为空");
        }
        if (content.length() > 2000) {
            throw new AppException("INVALID_REQUEST", "评论不能超过 2000 字");
        }

        // XSS 防护：HTML 转义用户输入
        content = HtmlUtils.htmlEscape(content);

        Moment moment = momentRepo.findById(momentId)
                .orElseThrow(() -> new AppException("MOMENT_NOT_FOUND", "动态不存在"));

        Comment comment = Comment.builder()
                .userId(userId)
                .momentId(momentId)
                .parentId(parentId)
                .content(content)
                .likeCount(0)
                .build();
        Comment saved = commentRepo.save(comment);

        // 更新动态评论数
        moment.setCommentCount(moment.getCommentCount() + 1);
        momentRepo.save(moment);

        // 触发通知
        notifService.create(
                moment.getUserId(), "COMMENT", "有人评论了你的动态",
                content.substring(0, Math.min(50, content.length())),
                userId, "MOMENT", momentId
        );

        return toDto(saved);
    }

    @Transactional(readOnly = true)
    public Page<CommentDto> list(Long momentId, int page, int size) {
        Pageable pageable = PageRequest.of(page, Math.min(size, 50));
        Page<Comment> comments = commentRepo
                .findByMomentIdAndParentIdIsNullOrderByCreatedAtDesc(momentId, pageable);
        // N+1 fix: 批量 fetch users 一次
        Map<Long, User> userMap = batchFetchUsers(comments.getContent());
        return comments.map(c -> toDto(c, userMap));
    }

    @Transactional(readOnly = true)
    public List<CommentDto> listReplies(Long parentId) {
        List<Comment> replies = commentRepo.findByParentIdOrderByCreatedAtAsc(parentId);
        // N+1 fix: 批量 fetch users 一次
        Map<Long, User> userMap = batchFetchUsers(replies);
        return replies.stream()
                .map(c -> toDto(c, userMap))
                .collect(Collectors.toList());
    }

    /**
     * 评论点赞 toggle：已赞则取消，未赞则点赞
     */
    @Transactional
    public boolean toggleCommentLike(Long userId, Long commentId) {
        Comment comment = commentRepo.findById(commentId)
                .orElseThrow(() -> new AppException("COMMENT_NOT_FOUND", "评论不存在"));

        boolean exists = commentLikeRepo.existsByUserIdAndCommentId(userId, commentId);
        if (exists) {
            commentLikeRepo.deleteByUserIdAndCommentId(userId, commentId);
            comment.setLikeCount(Math.max(0, comment.getLikeCount() - 1));
            commentRepo.save(comment);
            return false;
        }
        commentLikeRepo.save(CommentLike.builder().userId(userId).commentId(commentId).build());
        comment.setLikeCount(comment.getLikeCount() + 1);
        commentRepo.save(comment);
        return true;
    }

    @Transactional
    public void delete(Long commentId, Long userId) {
        Comment c = commentRepo.findById(commentId)
                .orElseThrow(() -> new AppException("COMMENT_NOT_FOUND", "评论不存在"));
        if (!c.getUserId().equals(userId)) {
            throw new AppException("FORBIDDEN", "无权操作");
        }
        commentRepo.deleteById(commentId);
        momentRepo.findById(c.getMomentId()).ifPresent(m -> {
            m.setCommentCount(Math.max(0, m.getCommentCount() - 1));
            momentRepo.save(m);
        });
    }

    /**
     * 批量 fetch 一组 comments 对应的 users (N+1 fix)
     * 用 findAllById 一条 SQL 拿全
     */
    private Map<Long, User> batchFetchUsers(List<Comment> comments) {
        if (comments.isEmpty()) return Map.of();
        Set<Long> userIds = new HashSet<>();
        for (Comment c : comments) {
            if (c.getUserId() != null) userIds.add(c.getUserId());
        }
        Map<Long, User> map = new HashMap<>();
        for (User u : userRepo.findAllById(userIds)) {
            map.put(u.getId(), u);
        }
        return map;
    }

    /** 批量版本：从预取的 userMap 取 User，不再逐条查库 */
    private CommentDto toDto(Comment c, Map<Long, User> userMap) {
        User u = userMap.get(c.getUserId());
        return new CommentDto(
                c.getId(),
                c.getMomentId(),
                c.getUserId(),
                u != null ? u.getNickname() : "未知",
                u != null ? u.getAvatarUrl() : null,
                c.getParentId(),
                c.getContent(),
                c.getLikeCount(),
                c.getCreatedAt()
        );
    }

    /** 单条版本：create() 等单条场景使用，保留原签名 */
    private CommentDto toDto(Comment c) {
        User u = userRepo.findById(c.getUserId()).orElse(null);
        return new CommentDto(
                c.getId(),
                c.getMomentId(),
                c.getUserId(),
                u != null ? u.getNickname() : "未知",
                u != null ? u.getAvatarUrl() : null,
                c.getParentId(),
                c.getContent(),
                c.getLikeCount(),
                c.getCreatedAt()
        );
    }
}
