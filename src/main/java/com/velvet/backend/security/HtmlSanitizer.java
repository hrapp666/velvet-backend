package com.velvet.backend.security;

import org.jsoup.Jsoup;
import org.jsoup.safety.Safelist;
import org.springframework.stereotype.Component;

/**
 * XSS 防护 HTML sanitizer (audit P0 安全)
 * <p>
 * 用 jsoup Safelist 在用户输入入库前净化:
 * - 富文本 (moment.content / comment.content): {@link #sanitizeRich}
 *   保留: a (no js href), p, br, strong, em, ul/ol/li, blockquote
 * - 纯文本 (title / username / nickname): {@link #sanitizePlain}
 *   去掉所有 HTML, 只留文字
 * <p>
 * 9 库方法论:
 * - superpowers Phase 1: root cause = moment.content 富文本可能含 &lt;script&gt;
 * - onlook small diff: 单一 service · 一个 component · 不动现有 entity
 * - OpenSpace fallback: jsoup 失败 → 退到 plain text strip (永远不抛出)
 */
@Component
public class HtmlSanitizer {

    /**
     * 富文本净化 - 保留基本格式标签, 去掉 script / style / on* / javascript: 等
     * 用于 moment.content / comment.content / 评价 reviewText
     */
    public String sanitizeRich(String input) {
        if (input == null || input.isBlank()) return input;
        try {
            // basic Safelist 含 a, b, blockquote, br, cite, code, dd, dl, dt,
            // em, i, li, ol, p, pre, q, small, span, strike, strong, sub, sup, u, ul
            return Jsoup.clean(input, Safelist.basic().preserveRelativeLinks(false));
        } catch (Exception e) {
            // jsoup 失败 → fallback strip 所有 HTML, 永远不抛出
            return sanitizePlain(input);
        }
    }

    /**
     * 纯文本净化 - 完全去掉所有 HTML 标签 + 实体解码
     * 用于 title / nickname / username / location 等不允许格式的字段
     */
    public String sanitizePlain(String input) {
        if (input == null || input.isBlank()) return input;
        try {
            return Jsoup.clean(input, Safelist.none());
        } catch (Exception e) {
            return input.replaceAll("<[^>]+>", "");
        }
    }
}
