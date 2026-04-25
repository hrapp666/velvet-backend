package com.velvet.backend.controller;

import com.velvet.backend.entity.Block;
import com.velvet.backend.exception.AppException;
import com.velvet.backend.service.BlockService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 拉黑管理 REST · Apple 1.2 UGC 合规
 *
 * <pre>
 * POST   /api/v1/blocks           · 拉黑用户
 * DELETE /api/v1/blocks/{userId}  · 解除拉黑
 * GET    /api/v1/blocks           · 我拉黑的列表 (分页)
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/blocks")
@RequiredArgsConstructor
public class BlockController {

    private final BlockService blockService;

    public record CreateBlockRequest(Long targetUserId, String reason) {}

    @PostMapping
    public Block create(@RequestBody CreateBlockRequest req) {
        if (req == null || req.targetUserId() == null) {
            throw new AppException("INVALID_REQUEST", "缺少 targetUserId");
        }
        return blockService.block(currentUserId(), req.targetUserId(), req.reason());
    }

    @DeleteMapping("/{targetUserId}")
    public void delete(@PathVariable Long targetUserId) {
        blockService.unblock(currentUserId(), targetUserId);
    }

    @GetMapping
    public Page<Map<String, Object>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return blockService.listBlocked(currentUserId(), page, size);
    }

    private Long currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof Long id)) {
            throw new AppException("UNAUTHORIZED", "请先登录");
        }
        return id;
    }
}
