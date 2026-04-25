package com.velvet.backend.controller;

import com.velvet.backend.entity.Notification;
import com.velvet.backend.exception.AppException;
import com.velvet.backend.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notifService;

    @GetMapping
    public Page<Notification> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return notifService.list(currentUserId(), page, size);
    }

    @GetMapping("/unread-count")
    public Map<String, Object> unreadCount() {
        return Map.of("count", notifService.unreadCount(currentUserId()));
    }

    @PostMapping("/{id}/read")
    public void markRead(@PathVariable Long id) {
        notifService.markRead(id, currentUserId());
    }

    @PostMapping("/read-all")
    public void markAllRead() {
        notifService.markAllRead(currentUserId());
    }

    private Long currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof Long id)) {
            throw new AppException("UNAUTHORIZED", "请先登录");
        }
        return id;
    }
}
