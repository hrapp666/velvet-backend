package com.velvet.backend.service;

import com.velvet.backend.exception.ContentViolationException;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
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
     * 图像内容审核 — v0 占位，永远放行。
     *
     * <p>v1 升级路径：
     * <pre>
     * // 阿里云绿网
     * GreenClient client = new GreenClient(accessKeyId, accessKeySecret, regionId);
     * ImageSyncScanRequest request = new ImageSyncScanRequest();
     * request.setScene("porn,terrorism");
     * request.setImageUrl(imageUrl);
     * ImageSyncScanResponse response = client.imageSyncScan(request);
     * return response.getSuggestion().equals("pass");
     * </pre>
     *
     * @param imageUrl MinIO / CDN 图片 URL
     * @return true 代表审核通过
     */
    public boolean moderateImage(String imageUrl) {
        // v0：不检查，永远 return true
        // v1：调阿里云 / 腾讯云 image moderation API，主人挂 key 后替换
        return true;
    }
}
