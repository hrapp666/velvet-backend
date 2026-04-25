package com.velvet.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public final class MomentRequests {

    public record CreateMomentRequest(
            @Size(max = 128) String title,
            @NotBlank @Size(max = 2000) String content,
            String coverUrl,
            List<String> mediaUrls,
            Boolean hasItem,
            Long itemPriceCents,
            Map<String, Object> itemAttributes,
            List<String> tags,
            String location,
            Double latitude,
            Double longitude,
            String visibility
    ) {}

    public record UpdateMomentRequest(
            String title,
            String content,
            List<String> mediaUrls,
            Long itemPriceCents,
            Map<String, Object> itemAttributes,
            List<String> tags,
            String visibility
    ) {}

    public record MomentDto(
            Long id,
            Long userId,
            String userNickname,
            String userAvatarUrl,
            String title,
            String content,
            String coverUrl,
            List<String> mediaUrls,
            Boolean hasItem,
            Long itemPriceCents,
            Map<String, Object> itemAttributes,
            List<String> tags,
            String location,
            Double latitude,
            Double longitude,
            /** 仅在 nearby 查询时填充（米），其他场景为 null */
            Double distanceMeters,
            String visibility,
            String status,
            Integer viewCount,
            Integer likeCount,
            Integer favoriteCount,
            Integer commentCount,
            Integer chatCount,
            Boolean liked,
            Boolean favorited,
            LocalDateTime createdAt
    ) {}

    private MomentRequests() {}
}
