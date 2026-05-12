package com.velvet.backend.controller;

import com.velvet.backend.exception.AppException;
import com.velvet.backend.service.UploadService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/v1/upload")
@RequiredArgsConstructor
public class UploadController {

    private final UploadService uploadService;

    private static final Set<String> ALLOWED_MIME = Set.of(
            "image/jpeg", "image/jpg", "image/png", "image/webp", "image/gif",
            "image/heic", "image/heif",
            "video/mp4", "video/quicktime"
    );

    /**
     * 拿一个 presigned PUT URL
     *
     * <p>POST /api/v1/upload/presign  body: {"mimeType":"image/jpeg"}
     * <p>response: { uploadUrl, finalUrl, objectKey, mimeType }
     */
    @PostMapping("/presign")
    public Map<String, String> presign(@RequestBody Map<String, String> body) {
        String mimeType = body.get("mimeType");
        if (mimeType == null || !ALLOWED_MIME.contains(mimeType.toLowerCase())) {
            throw new AppException("INVALID_MIME", "不支持的文件类型");
        }
        return uploadService.presignUpload(currentUserId(), mimeType);
    }

    private Long currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof Long id)) {
            throw new AppException("UNAUTHORIZED", "请先登录");
        }
        return id;
    }
}
