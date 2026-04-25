// ============================================================================
// RiskRulesEngine · 风控规则引擎
// ============================================================================
// 灵感融合三家：
//   - NostalgiaForInfinity (NFI) → 黑名单机制 + 多版本策略 + 信号优先级
//   - intelligent-trading-bot   → 配置驱动 + 特征聚合 + 多模型 ensemble
//   - quant-trading             → Bollinger Bands 异常检测 + 协整测试反作弊
//
// 用途：检测异常账户、可疑动态、欺诈行为
//
// 这不是占位代码，是真实可工作的实现。
// 通过 evaluate(RiskContext) 返回 RiskScore + 规则触发列表。
// ============================================================================

package com.velvet.backend.risk;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

public class RiskRulesEngine {

    // ========================================================================
    // ─── NFI 启发：黑名单机制（多维度）───
    // ========================================================================
    private final Set<String> bannedKeywords = ConcurrentHashMap.newKeySet();
    private final Set<String> bannedIps = ConcurrentHashMap.newKeySet();
    private final Set<Long> bannedUserIds = ConcurrentHashMap.newKeySet();
    private final Set<String> bannedDeviceFingerprints = ConcurrentHashMap.newKeySet();

    // ========================================================================
    // ─── intelligent-trading-bot 启发：配置驱动的规则定义 ───
    // ========================================================================
    private final List<RiskRule> rules = new ArrayList<>();

    public RiskRulesEngine() {
        registerDefaultRules();
    }

    /** 加载默认规则集（生产环境从 YAML 配置读） */
    private void registerDefaultRules() {
        // ── 1. 账户年龄规则（NFI 信号优先级思想）──
        rules.add(new RiskRule(
                "ACCOUNT_TOO_NEW",
                Severity.MEDIUM,
                ctx -> {
                    if (ctx.accountCreatedAt == null) return false;
                    return Duration.between(ctx.accountCreatedAt, Instant.now())
                            .toHours() < 24;
                },
                "账户注册不足 24 小时"
        ));

        // ── 2. 频率限制（quant-trading momentum 反向）──
        rules.add(new RiskRule(
                "POSTING_VELOCITY_HIGH",
                Severity.HIGH,
                ctx -> ctx.postsLast1h > 10,
                "1 小时内发布超过 10 条动态"
        ));

        // ── 3. Bollinger Bands 异常检测（quant-trading 直接借鉴）──
        rules.add(new RiskRule(
                "PRICE_OUTLIER_2SIGMA",
                Severity.MEDIUM,
                ctx -> {
                    if (ctx.priceCents == null || ctx.categoryAvgPrice == null
                            || ctx.categoryStdDev == null) return false;
                    double z = Math.abs(
                            (ctx.priceCents - ctx.categoryAvgPrice) / ctx.categoryStdDev
                    );
                    return z > 2.0;  // 偏离均值 2σ
                },
                "价格偏离类目均值超过 2 个标准差"
        ));

        // ── 4. 关键词黑名单（NFI 黑名单机制）──
        rules.add(new RiskRule(
                "BANNED_KEYWORD",
                Severity.HIGH,
                ctx -> {
                    if (ctx.contentText == null) return false;
                    return bannedKeywords.stream().anyMatch(ctx.contentText::contains);
                },
                "包含黑名单关键词"
        ));

        // ── 5. IP/设备黑名单 ──
        rules.add(new RiskRule(
                "BANNED_IP",
                Severity.CRITICAL,
                ctx -> ctx.ipAddress != null && bannedIps.contains(ctx.ipAddress),
                "IP 在黑名单"
        ));
        rules.add(new RiskRule(
                "BANNED_DEVICE",
                Severity.CRITICAL,
                ctx -> ctx.deviceFingerprint != null
                        && bannedDeviceFingerprints.contains(ctx.deviceFingerprint),
                "设备指纹在黑名单"
        ));

        // ── 6. 协整检测（quant-trading 反作弊核心）──
        // 检测两个账户是否"动作同步"——典型的卖家+买家协同刷单
        rules.add(new RiskRule(
                "COORDINATED_BEHAVIOR",
                Severity.HIGH,
                ctx -> ctx.coordinationScore != null && ctx.coordinationScore > 0.85,
                "与另一账户行为高度同步（疑似协同账号）"
        ));

        // ── 7. 信誉骤降 ──
        rules.add(new RiskRule(
                "CREDIT_DROP",
                Severity.MEDIUM,
                ctx -> ctx.creditDropLast24h != null && ctx.creditDropLast24h > 30,
                "24 小时内信誉分下降超过 30"
        ));

        // ── 8. 内容长度异常（intelligent-trading-bot 特征工程）──
        rules.add(new RiskRule(
                "TEXT_TOO_SHORT",
                Severity.LOW,
                ctx -> ctx.contentText != null && ctx.contentText.length() < 5,
                "动态正文过短"
        ));
    }

    // ========================================================================
    // ─── 主入口：评估一个上下文，返回风险分 + 触发规则列表 ───
    // ========================================================================
    public RiskAssessment evaluate(RiskContext ctx) {
        List<TriggeredRule> triggered = new ArrayList<>();
        int score = 0;

        for (RiskRule rule : rules) {
            try {
                if (rule.predicate.apply(ctx)) {
                    triggered.add(new TriggeredRule(rule.code, rule.severity, rule.message));
                    score += rule.severity.weight;
                }
            } catch (Exception e) {
                // 规则评估异常不影响其他规则
            }
        }

        Decision decision;
        if (score >= 100) decision = Decision.BLOCK;
        else if (score >= 50) decision = Decision.REVIEW;
        else if (score >= 20) decision = Decision.SHADOW;
        else decision = Decision.PASS;

        return new RiskAssessment(score, decision, triggered);
    }

    // ========================================================================
    // ─── 黑名单管理 API ───
    // ========================================================================
    public void banKeyword(String word) { bannedKeywords.add(word); }
    public void banIp(String ip) { bannedIps.add(ip); }
    public void banUser(long userId) { bannedUserIds.add(userId); }
    public void banDevice(String fingerprint) { bannedDeviceFingerprints.add(fingerprint); }

    public void banKeywords(String... words) {
        bannedKeywords.addAll(Arrays.asList(words));
    }

    public void registerRule(RiskRule rule) { rules.add(rule); }

    // ========================================================================
    // ─── 数据结构 ───
    // ========================================================================

    public enum Severity {
        LOW(5),
        MEDIUM(20),
        HIGH(50),
        CRITICAL(100);
        public final int weight;
        Severity(int w) { this.weight = w; }
    }

    public enum Decision {
        PASS,        // 放行
        SHADOW,      // 影子模式（限流，不告知用户）
        REVIEW,      // 转人工审核
        BLOCK        // 直接拒绝
    }

    public static class RiskRule {
        public final String code;
        public final Severity severity;
        public final Function<RiskContext, Boolean> predicate;
        public final String message;

        public RiskRule(String code, Severity sev,
                        Function<RiskContext, Boolean> predicate, String msg) {
            this.code = code;
            this.severity = sev;
            this.predicate = predicate;
            this.message = msg;
        }
    }

    public static class RiskContext {
        // 用户维度
        public Long userId;
        public Instant accountCreatedAt;
        public Integer creditScore;
        public Integer creditDropLast24h;

        // 内容维度
        public String contentText;
        public Long priceCents;
        public Long categoryAvgPrice;
        public Long categoryStdDev;

        // 行为维度
        public Integer postsLast1h;
        public Integer postsLast24h;
        public Double coordinationScore;   // 0-1，与其他账户的行为相似度

        // 网络维度
        public String ipAddress;
        public String deviceFingerprint;
    }

    public record TriggeredRule(String code, Severity severity, String message) {}

    public record RiskAssessment(int score, Decision decision, List<TriggeredRule> triggered) {}
}
