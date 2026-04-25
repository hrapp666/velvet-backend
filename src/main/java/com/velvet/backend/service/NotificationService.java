package com.velvet.backend.service;

import com.velvet.backend.entity.Notification;
import com.velvet.backend.repository.NotificationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class NotificationService {

    private final NotificationRepository notifRepo;

    /**
     * 异步触发通知（不阻塞业务流程）。
     * 业务方法（点赞/评论/关注）调用本方法创建通知。
     */
    @Async
    @Transactional
    public void create(Long recipientId, String type, String title, String content,
                       Long actorId, String targetType, Long targetId) {
        // 不给自己发通知
        if (recipientId.equals(actorId)) return;

        Notification n = Notification.builder()
                .userId(recipientId)
                .type(type)
                .title(title)
                .content(content)
                .actorId(actorId)
                .targetType(targetType)
                .targetId(targetId)
                .isRead(false)
                .build();
        notifRepo.save(n);
    }

    @Transactional(readOnly = true)
    public Page<Notification> list(Long userId, int page, int size) {
        Pageable pageable = PageRequest.of(page, Math.min(size, 50));
        return notifRepo.findByUserIdOrderByCreatedAtDesc(userId, pageable);
    }

    @Transactional(readOnly = true)
    public long unreadCount(Long userId) {
        return notifRepo.countByUserIdAndIsReadFalse(userId);
    }

    @Transactional
    public void markRead(Long notifId, Long userId) {
        notifRepo.findById(notifId).ifPresent(n -> {
            if (n.getUserId().equals(userId)) {
                n.setIsRead(true);
                notifRepo.save(n);
            }
        });
    }

    @Transactional
    public void markAllRead(Long userId) {
        // 简化实现：拉前 200 条标已读
        notifRepo.findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(0, 200))
                .forEach(n -> {
                    if (!n.getIsRead()) {
                        n.setIsRead(true);
                        notifRepo.save(n);
                    }
                });
    }
}
