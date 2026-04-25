// Claude Agent execution skeleton (Java port).
//
// Distilled from KeygraphHQ/shannon — apps/worker/src/ai/claude-executor.ts.
//
// 注意：Anthropic 没有官方 Java SDK 的 Agent SDK 等价物。本文件直接调用
// Anthropic Messages REST API（/v1/messages）模拟 query() 的 streaming 循环。
//
// 如果只需要 single-turn 调用（不需要 tool use 循环），可直接用本文件。
// 如果需要 multi-turn 工具调用 + 长会话 + 子代理委派，建议改成调用一个
// Python/TS sidecar service 跑 claude-agent-sdk，本类只做 HTTP gateway。
//
// 依赖：仅 java.net.http (JDK 17+) + 一个 JSON 库（推荐 Jackson，Spring Boot 已含）。

package com.velvet.backend.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Set;
import java.util.function.Consumer;

public final class ClaudeRunner {

    public enum ModelTier { SMALL, MEDIUM, LARGE }

    private static final String API_URL = "https://api.anthropic.com/v1/messages";
    private static final String API_VERSION = "2023-06-01";
    private static final long HEARTBEAT_INTERVAL_MS = 30_000;

    private static final Set<String> NON_RETRYABLE_NAMES = Set.of(
            "AuthenticationError",
            "PermissionError",
            "InvalidRequestError",
            "RequestTooLargeError",
            "ConfigurationError"
    );

    private final HttpClient http;
    private final ObjectMapper json;
    private final String apiKey;

    public ClaudeRunner(String apiKey) {
        this.apiKey = apiKey;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        this.json = new ObjectMapper();
    }

    public static String resolveModel(ModelTier tier) {
        return switch (tier) {
            case SMALL -> getEnvOr("ANTHROPIC_SMALL_MODEL", "claude-haiku-4-5-20251001");
            case MEDIUM -> getEnvOr("ANTHROPIC_MEDIUM_MODEL", "claude-sonnet-4-6");
            case LARGE -> getEnvOr("ANTHROPIC_LARGE_MODEL", "claude-opus-4-6");
        };
    }

    private static String getEnvOr(String key, String def) {
        String v = System.getenv(key);
        return v != null ? v : def;
    }

    public static class RunOptions {
        public String prompt;
        public String systemPrompt;
        public ModelTier modelTier = ModelTier.MEDIUM;
        public int maxTokens = 8192;
        public Duration timeout = Duration.ofMinutes(10);
        public Consumer<String> onProgress; // event description
        public boolean disableHeartbeat = false;
    }

    public static class RunResult {
        public String result;
        public boolean success;
        public long durationMs;
        public int turns; // always 1 for single-shot Messages API
        public double costUsd;
        public String model;
        public boolean apiErrorDetected;
        public String error;
        public String errorType;
        public Boolean retryable;
    }

    public static class SpendingCapException extends RuntimeException {
        public SpendingCapException(String msg) { super(msg); }
    }

    public static boolean isSpendingCapBehavior(int turns, double cost, String result) {
        if (cost > 0) return false;
        if (turns >= 5) return true;
        String lower = result == null ? "" : result.toLowerCase();
        return lower.contains("spending") || lower.contains("billing");
    }

    public static boolean isRetryableError(Throwable e) {
        return !NON_RETRYABLE_NAMES.contains(e.getClass().getSimpleName());
    }

    public RunResult run(RunOptions opts) {
        long start = System.currentTimeMillis();
        RunResult r = new RunResult();
        r.model = resolveModel(opts.modelTier);

        try {
            ObjectNode body = json.createObjectNode();
            body.put("model", r.model);
            body.put("max_tokens", opts.maxTokens);
            if (opts.systemPrompt != null) body.put("system", opts.systemPrompt);
            ArrayNode messages = body.putArray("messages");
            ObjectNode msg = messages.addObject();
            msg.put("role", "user");
            msg.put("content", opts.prompt);

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(API_URL))
                    .timeout(opts.timeout)
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", API_VERSION)
                    .header("content-type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                    .build();

            if (opts.onProgress != null) opts.onProgress.accept("start");

            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());

            if (resp.statusCode() == 401) throw new RuntimeException("AuthenticationError: " + resp.body());
            if (resp.statusCode() == 403) throw new RuntimeException("PermissionError: " + resp.body());
            if (resp.statusCode() == 400) throw new RuntimeException("InvalidRequestError: " + resp.body());
            if (resp.statusCode() == 413) throw new RuntimeException("RequestTooLargeError: " + resp.body());
            if (resp.statusCode() == 429) {
                r.apiErrorDetected = true;
                throw new RuntimeException("RateLimitError: " + resp.body());
            }
            if (resp.statusCode() >= 500) throw new RuntimeException("ServerError: " + resp.body());
            if (resp.statusCode() != 200) throw new RuntimeException("UnknownError: " + resp.body());

            JsonNode root = json.readTree(resp.body());
            JsonNode contentArr = root.get("content");
            StringBuilder sb = new StringBuilder();
            if (contentArr != null && contentArr.isArray()) {
                for (JsonNode block : contentArr) {
                    if ("text".equals(block.path("type").asText())) {
                        sb.append(block.path("text").asText());
                    }
                }
            }
            r.result = sb.toString();
            r.turns = 1;
            r.costUsd = estimateCost(root, r.model);
            r.success = true;

            if (isSpendingCapBehavior(r.turns, r.costUsd, r.result)) {
                throw new SpendingCapException(
                        "Spending cap likely reached (cost=$" + r.costUsd + ")");
            }

            if (opts.onProgress != null) opts.onProgress.accept("complete");

        } catch (Exception e) {
            r.success = false;
            r.error = e.getMessage();
            r.errorType = e.getClass().getSimpleName();
            r.retryable = isRetryableError(e);
            if (opts.onProgress != null) opts.onProgress.accept("error: " + e.getMessage());
        }
        r.durationMs = System.currentTimeMillis() - start;
        return r;
    }

    /** Approximate USD cost from input/output tokens. Update prices as Anthropic publishes. */
    private static double estimateCost(JsonNode root, String model) {
        JsonNode usage = root.get("usage");
        if (usage == null) return 0.0;
        long inTok = usage.path("input_tokens").asLong(0);
        long outTok = usage.path("output_tokens").asLong(0);

        // Rough per-million-token rates (USD). Update for current pricing.
        double inRate, outRate;
        if (model.contains("haiku")) { inRate = 1.0; outRate = 5.0; }
        else if (model.contains("opus")) { inRate = 15.0; outRate = 75.0; }
        else { inRate = 3.0; outRate = 15.0; } // sonnet default

        return (inTok / 1_000_000.0) * inRate + (outTok / 1_000_000.0) * outRate;
    }
}
