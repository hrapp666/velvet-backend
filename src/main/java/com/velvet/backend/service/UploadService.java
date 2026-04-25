package com.velvet.backend.service;

import com.velvet.backend.exception.AppException;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.http.Method;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Velvet 图片上传服务
 *
 * <p>架构：前端调 /api/v1/upload/presign 拿 presigned PUT URL，
 * 然后直传 MinIO，绕过后端流量。完成后只把最终 URL 上报给 /api/v1/moments。
 *
 * <p>这种 presigned 模式好处：
 *   - 后端不消耗带宽
 *   - 前端可以并发上传多张
 *   - 大文件不会撑爆后端 JVM 内存
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UploadService {

    @Value("${velvet.upload.s3.endpoint:http://minio:9000}")
    private String endpoint;

    /**
     * 用于生成 presigned URL 的公网可达端点。MinIO Java SDK 用 {@code endpoint} 字段构造
     * 签名 URL 的 host:port，Docker 内部地址 {@code http://minio:9000} 手机/浏览器访问不到。
     * 这里单独配置一个公网端点（trycloudflare / CF tunnel / 反代域名），仅用于"签名"。
     * 真正的对象上传由前端直传该公网地址，请求经反代到 MinIO 后由 MinIO 校验签名。
     */
    @Value("${velvet.upload.s3.public-endpoint:}")
    private String publicEndpoint;

    @Value("${velvet.upload.s3.bucket:velvet-media}")
    private String bucket;

    @Value("${velvet.upload.s3.region:us-east-1}")
    private String region;

    @Value("${velvet.upload.s3.access-key:}")
    private String accessKey;

    @Value("${velvet.upload.s3.secret-key:}")
    private String secretKey;

    @Value("${velvet.upload.s3.path-style:true}")
    private boolean pathStyle;

    @Value("${velvet.upload.cdn-base-url:https://cdn.velvet.app}")
    private String cdnBaseUrl;

    /** 后端→MinIO 内部读写用的客户端（endpoint 为 Docker 内网地址）。 */
    private MinioClient client;

    /** 仅用于生成给手机/浏览器的 presigned URL 的客户端（endpoint 为公网地址）。 */
    private MinioClient signerClient;

    /**
     * 初始化 S3 客户端。MinIO SDK 兼容所有 S3 协议存储：
     * - MinIO (自托管): path-style=true (默认)
     * - AWS S3: path-style=false, region 必须匹配 bucket 所在区域
     * - 阿里 OSS / 腾讯 COS: path-style=false, endpoint 用对应区域地址
     */
    @PostConstruct
    void init() {
        if (accessKey == null || accessKey.isBlank()) {
            log.warn("S3 accessKey 未配置 — UploadService 将无法工作");
            return;
        }
        this.client = buildClient(endpoint);
        String signerEndpoint = (publicEndpoint == null || publicEndpoint.isBlank())
                ? endpoint
                : publicEndpoint;
        this.signerClient = signerEndpoint.equals(endpoint)
                ? this.client
                : buildClient(signerEndpoint);
        log.info(
                "S3 clients initialized: endpoint={}, publicEndpoint={}, bucket={}, region={}, pathStyle={}",
                endpoint, signerEndpoint, bucket, region, pathStyle);
    }

    private MinioClient buildClient(String ep) {
        var c = MinioClient.builder()
                .endpoint(ep)
                .credentials(accessKey, secretKey)
                .region(region)
                .build();
        if (!pathStyle) {
            c.enableVirtualStyleEndpoint();
        }
        return c;
    }

    /**
     * 生成 presigned PUT URL，前端拿到后直接 PUT 到这个 URL 上传。
     *
     * @param userId   上传用户 ID
     * @param mimeType 文件 MIME（image/jpeg / image/png / video/mp4 等）
     * @return Map(uploadUrl, finalUrl, objectKey)
     */
    public Map<String, String> presignUpload(Long userId, String mimeType) {
        if (client == null) {
            throw new AppException("UPLOAD_NOT_CONFIGURED", "上传服务未配置");
        }
        String ext = guessExt(mimeType);
        String date = LocalDate.now().toString();
        String objectKey = String.format("uploads/%s/u%d/%s%s",
                date, userId, UUID.randomUUID().toString().replace("-", ""), ext);

        try {
            String uploadUrl = signerClient.getPresignedObjectUrl(
                    GetPresignedObjectUrlArgs.builder()
                            .method(Method.PUT)
                            .bucket(bucket)
                            .object(objectKey)
                            .expiry(15, TimeUnit.MINUTES)
                            .build()
            );

            // 公开访问 URL（通过 CDN 反代到 MinIO）
            String finalUrl = cdnBaseUrl.replaceAll("/$", "") + "/" + bucket + "/" + objectKey;

            return Map.of(
                    "uploadUrl", uploadUrl,
                    "finalUrl", finalUrl,
                    "objectKey", objectKey,
                    "mimeType", mimeType
            );
        } catch (Exception e) {
            log.error("Presign upload failed for userId={}", userId, e);
            throw new AppException("UPLOAD_PRESIGN_FAILED", "生成上传链接失败");
        }
    }

    private String guessExt(String mime) {
        if (mime == null) return "";
        return switch (mime.toLowerCase()) {
            case "image/jpeg", "image/jpg" -> ".jpg";
            case "image/png" -> ".png";
            case "image/webp" -> ".webp";
            case "image/gif" -> ".gif";
            case "video/mp4" -> ".mp4";
            case "video/quicktime" -> ".mov";
            default -> "";
        };
    }
}
