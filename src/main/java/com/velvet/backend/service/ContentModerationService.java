package com.velvet.backend.service;

import com.velvet.backend.exception.ContentViolationException;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

/**
 * 内容审核服务 — v0 基础版（本地词表 + 空间图像占位）
 *
 * <p>架构：
 * <ul>
 *   <li>启动时从 classpath:moderation/forbidden_words.txt 加载词表到 {@code Set<String>}</li>
 *   <li>moderateText：对文本 normalize（小写 + 去空格）后做 contains 匹配</li>
 *   <li>moderateImage：v0 永远放行，v1 接入阿里云 / 腾讯云图审 API 后替换</li>
 * </ul>
 *
 * <p>安全原则：
 * <ul>
 *   <li>违规时只返回"内容含违规信息"，不暴露具体触发词</li>
 *   <li>审计日志只记录 context（用户 ID + 场景），不记录触发词（隐私平衡）</li>
 * </ul>
 */
@Slf4j
@Service
public class ContentModerationService {

    private static final String WORD_LIST_PATH = "moderation/forbidden_words.txt";

    /** 词表：全部小写，启动时一次性加载，运行期只读，线程安全 */
    private Set<String> forbiddenWords;

    /** 阿里云绿网 AccessKey ID — 空 = 未配置 = v0 fallback (永远放行) */
    @Value("${moderation.aliyun.access-key-id:}")
    private String aliyunAccessKeyId;

    /** 阿里云绿网 AccessKey Secret — 与 ID 同步配置 */
    @Value("${moderation.aliyun.access-key-secret:}")
    private String aliyunAccessKeySecret;

    /** 阿里云绿网区域，默认 cn-shanghai */
    @Value("${moderation.aliyun.region:cn-shanghai}")
    private String aliyunRegion;

    /** 图审失败 (网络 / 鉴权 / SDK 异常) 时的 fail-open 开关 — 默认 true 不阻断流程 */
    @Value("${moderation.image.fail-open:true}")
    private boolean imageFailOpen;

    @PostConstruct
    public void init() {
        Set<String> words = new HashSet<>();
        try {
            ClassPathResource resource = new ClassPathResource(WORD_LIST_PATH);
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String trimmed = line.strip();
                    // 跳过空行和注释行
                    if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                        continue;
                    }
                    words.add(trimmed.toLowerCase());
                }
            }
            // 不可变集合 — 运行期无法被外部修改
            this.forbiddenWords = Set.copyOf(words);
            log.info("ContentModerationService loaded {} forbidden words", this.forbiddenWords.size());
        } catch (Exception e) {
            // 词表加载失败不应阻断应用启动，但必须告警
            log.error("Failed to load forbidden word list from {}, moderation will be DISABLED", WORD_LIST_PATH, e);
            this.forbiddenWords = Set.of();
        }
    }

    /**
     * 文本内容审核。
     *
     * <p>normalize 策略（v25 reviewer M2 加强）：小写化 + 去除所有:
     * <ul>
     *   <li>ASCII 空白 {@code \s}</li>
     *   <li>Unicode 空白分隔符 {@code \p{Z}}(含全角空格 U+3000)</li>
     *   <li>Unicode 标点 {@code \p{P}}(句点 短横 下划线 等)</li>
     *   <li>Unicode 符号 {@code \p{S}}(数学符号 货币等)</li>
     * </ul>
     *
     * <p>这样覆盖了"加 我 微 信" / "加\u3000我\u3000微\u3000信" /
     * "加.我.微.信" / "加-我-v-信" 等常见规避写法。
     *
     * @param text    待检文本，null 或空白直接放行
     * @param context 审计上下文（如 "moment_title_user_123"），写入 warn log
     * @throws ContentViolationException 如果检测到违规词，消息不含具体词
     */
    public void moderateText(String text, String context) {
        if (text == null || text.isBlank()) {
            return;
        }
        if (forbiddenWords.isEmpty()) {
            return;
        }
        // normalize 加强版:白名单 · 只保留 Unicode 字母 + 数字 · 其他全去
        // 比黑名单更稳 · Java Pattern 的 \p{P} 在某些场景不覆盖 ASCII '.' '-' 等
        // "加.我.微.信" / "加\u3000我\u3000微\u3000信" / "加 + 我 + 微 + 信"
        // 都 normalize 成 "加我微信" 命中词表
        final String normalized = text.toLowerCase().replaceAll("[^\\p{L}\\p{N}]+", "");
        for (String word : forbiddenWords) {
            if (normalized.contains(word)) {
                // 审计日志：记录 context 和行为类型，不记录触发词
                log.warn("Content violation detected in context=[{}]", context);
                throw new ContentViolationException("内容含违规信息");
            }
        }
    }

    /**
     * 图像内容审核 — v1 框架（env-var gated）。
     *
     * <p>路由策略：
     * <ul>
     *   <li>未配置 AccessKey → v0 fallback：永远 return true（不阻断主流程，保持向后兼容）</li>
     *   <li>已配置 AccessKey → 调阿里云绿网 imageSyncScan，suggestion=pass 才放行</li>
     *   <li>SDK 异常 → 看 {@code moderation.image.fail-open}：true=放行（默认）/ false=拦截</li>
     * </ul>
     *
     * <p>主人挂 key 步骤（application.yml 或环境变量）：
     * <pre>
     * moderation:
     *   aliyun:
     *     access-key-id: ${ALIYUN_GREEN_AK_ID:}
     *     access-key-secret: ${ALIYUN_GREEN_AK_SECRET:}
     *     region: cn-shanghai
     *   image:
     *     fail-open: true
     * </pre>
     *
     * @param imageUrl MinIO / CDN 图片 URL，null 或空白直接放行
     * @return true 代表审核通过 / 未配置 / fail-open 兜底
     */
    public boolean moderateImage(String imageUrl) {
        if (imageUrl == null || imageUrl.isBlank()) {
            return true;
        }
        if (aliyunAccessKeyId == null || aliyunAccessKeyId.isBlank()
                || aliyunAccessKeySecret == null || aliyunAccessKeySecret.isBlank()) {
            // v0 fallback：未配置 AccessKey，保持原行为永远放行
            // 警告级日志只打印一次的责任不在此方法 (Spring 启动时单次警告更合适，由后续 PR 接管)
            return true;
        }
        try {
            return callAliyunGreen(imageUrl);
        } catch (Exception e) {
            log.error("Image moderation call failed url=[{}] failOpen={}", imageUrl, imageFailOpen, e);
            return imageFailOpen;
        }
    }

    /**
     * 阿里云绿网 imageSyncScan 调用。
     *
     * <p>当前为占位 — 真实接入需要 {@code com.aliyun:green20220302} 依赖。挂 key 后再补 SDK + 实现。
     * 维持方法签名稳定，后续替换不影响 {@link #moderateImage(String)}。
     *
     * @return suggestion="pass" 时 true，否则 false
     */
    private boolean callAliyunGreen(String imageUrl) {
        // TODO(v1.1)：pom.xml 加入 green20220302 SDK + 真实调用
        // GreenClient client = new GreenClient(aliyunAccessKeyId, aliyunAccessKeySecret, aliyunRegion);
        // ImageSyncScanRequest req = new ImageSyncScanRequest();
        // req.setScene("porn,terrorism,ad");
        // req.setImageUrl(imageUrl);
        // var resp = client.imageSyncScan(req);
        // return "pass".equals(resp.getSuggestion());
        log.warn("Aliyun Green SDK not yet wired; falling back to permissive pass for url=[{}]", imageUrl);
        return true;
    }
}
