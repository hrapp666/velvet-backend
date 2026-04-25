// Shannon-style multi-agent orchestrator (Java port).
//
// Distilled from KeygraphHQ/shannon — apps/worker/src/temporal/workflows.ts.
//
// Core patterns ported to Java 17 + CompletableFuture:
// 1. Custom concurrency limiter (Semaphore-bounded executor) — completion-order results
// 2. Four retry profiles (production / testing / subscription / preflight)
// 3. shouldSkip predicate for resume-from-checkpoint
// 4. Pipeline aggregation via CompletableFuture.allOf (graceful degradation)
// 5. Sequential phase + parallel pipeline composition
//
// 适配场景：Velvet 后端 — 注册流程多 agent 风控、AI 内容审核、自动 fix 等异步流程编排。
// 包路径：com.velvet.backend.agent
//
// 依赖（Maven）：仅 JDK 17+ ，无第三方依赖。Spring Boot 项目可直接 @Component 化。

package com.velvet.backend.agent;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

public final class AgentOrchestrator {

    // ========================================================================
    // Retry profiles — mirror Shannon's 4 proxyActivities configurations
    // ========================================================================

    private static final Set<String> NON_RETRYABLE_ERROR_TYPES = Set.of(
            "AuthenticationError",
            "PermissionError",
            "InvalidRequestError",
            "RequestTooLargeError",
            "ConfigurationError",
            "InvalidTargetError",
            "ExecutionLimitError"
    );

    public record RetryProfile(
            String name,
            Duration initialInterval,
            Duration maximumInterval,
            double backoffCoefficient,
            int maximumAttempts,
            Duration startToCloseTimeout,
            Set<String> nonRetryableErrorTypes
    ) {}

    public static final Map<String, RetryProfile> RETRY_PROFILES = Map.of(
            "production", new RetryProfile(
                    "production",
                    Duration.ofMinutes(5),
                    Duration.ofMinutes(30),
                    2.0,
                    50,
                    Duration.ofHours(2),
                    NON_RETRYABLE_ERROR_TYPES),
            "testing", new RetryProfile(
                    "testing",
                    Duration.ofSeconds(10),
                    Duration.ofSeconds(30),
                    2.0,
                    5,
                    Duration.ofMinutes(30),
                    NON_RETRYABLE_ERROR_TYPES),
            "subscription", new RetryProfile(
                    "subscription",
                    Duration.ofMinutes(5),
                    Duration.ofHours(6), // Anthropic 5h+ rolling rate-limit window
                    2.0,
                    100,
                    Duration.ofHours(8),
                    NON_RETRYABLE_ERROR_TYPES),
            "preflight", new RetryProfile(
                    "preflight",
                    Duration.ofSeconds(10),
                    Duration.ofMinutes(1),
                    2.0,
                    3,
                    Duration.ofMinutes(2),
                    NON_RETRYABLE_ERROR_TYPES)
    );

    public static class ExecutionLimitException extends RuntimeException {
        public ExecutionLimitException(String msg) { super(msg); }
    }

    // ========================================================================
    // Retry runner — replaces Temporal's proxyActivities retry semantics
    // ========================================================================

    public static <T> T runWithRetry(
            Supplier<T> fn,
            RetryProfile profile,
            BiConsumer<Integer, Throwable> onRetry
    ) {
        Throwable lastErr = null;
        long delayMs = profile.initialInterval().toMillis();

        for (int attempt = 1; attempt <= profile.maximumAttempts(); attempt++) {
            try {
                return runWithTimeout(fn, profile.startToCloseTimeout());
            } catch (Throwable e) {
                lastErr = unwrap(e);
                String name = lastErr.getClass().getSimpleName();
                if (profile.nonRetryableErrorTypes().contains(name)) {
                    sneakyThrow(lastErr);
                }
                if (attempt == profile.maximumAttempts()) break;

                if (onRetry != null) onRetry.accept(attempt, lastErr);
                sleepUninterruptibly(delayMs);
                delayMs = Math.min(
                        (long) (delayMs * profile.backoffCoefficient()),
                        profile.maximumInterval().toMillis()
                );
            }
        }
        sneakyThrow(lastErr);
        return null; // unreachable
    }

    private static <T> T runWithTimeout(Supplier<T> fn, Duration timeout) {
        ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "agent-timeout-runner");
            t.setDaemon(true);
            return t;
        });
        try {
            return CompletableFuture.supplyAsync(fn, exec)
                    .get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new ExecutionLimitException("Activity exceeded " + timeout + " timeout");
        } catch (Exception e) {
            throw new RuntimeException(unwrap(e));
        } finally {
            exec.shutdownNow();
        }
    }

    // ========================================================================
    // Concurrency limiter — Shannon's runWithConcurrencyLimit semantics.
    // Returns settled results in COMPLETION ORDER. Callers must key on
    // result fields (e.g. agent name), not list index.
    // ========================================================================

    public sealed interface SettledResult<T> permits SettledOk, SettledErr {}
    public record SettledOk<T>(T value) implements SettledResult<T> {}
    public record SettledErr<T>(Throwable reason) implements SettledResult<T> {}

    public static <T> List<SettledResult<T>> runWithConcurrencyLimit(
            List<Supplier<CompletableFuture<T>>> thunks,
            int limit
    ) {
        Semaphore sem = new Semaphore(limit);
        ConcurrentLinkedQueue<SettledResult<T>> results = new ConcurrentLinkedQueue<>();
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        for (Supplier<CompletableFuture<T>> thunk : thunks) {
            try {
                sem.acquire();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
            CompletableFuture<Void> f = thunk.get()
                    .handle((value, err) -> {
                        if (err != null) {
                            results.add(new SettledErr<>(unwrap(err)));
                        } else {
                            results.add(new SettledOk<>(value));
                        }
                        sem.release();
                        return null;
                    });
            futures.add(f);
        }

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        return new ArrayList<>(results);
    }

    // ========================================================================
    // Pipeline state — replaces Temporal workflow state
    // ========================================================================

    public static class AgentMetrics {
        public double costUsd;
        public int numTurns;
        public long durationMs;
        public String model;
    }

    public static class PipelineState {
        public String status = "running"; // running | completed | failed
        public String currentPhase;
        public String currentAgent;
        public final List<String> completedAgents = new ArrayList<>();
        public String failedAgent;
        public String error;
        public final long startTime = System.currentTimeMillis();
        public final java.util.Map<String, AgentMetrics> agentMetrics = new java.util.HashMap<>();
    }

    public record ResumeState(List<String> completedAgents, String checkpointHash) {}

    public static Predicate<String> makeShouldSkip(ResumeState resumeState) {
        if (resumeState == null) return name -> false;
        Set<String> completed = Set.copyOf(resumeState.completedAgents());
        return completed::contains;
    }

    // ========================================================================
    // Sequential phase runner
    // ========================================================================

    public static void runSequentialPhase(
            PipelineState state,
            String phaseName,
            String agentName,
            Predicate<String> shouldSkip,
            Supplier<AgentMetrics> runAgent
    ) {
        if (shouldSkip.test(agentName)) {
            state.completedAgents.add(agentName);
            return;
        }
        state.currentPhase = phaseName;
        state.currentAgent = agentName;
        state.agentMetrics.put(agentName, runAgent.get());
        state.completedAgents.add(agentName);
    }

    // ========================================================================
    // Parallel pipeline with primary→followup pattern (Shannon vuln→exploit shape)
    // ========================================================================

    public record PipelineConfig(
            String kind,
            String primaryAgent,
            String followupAgent,
            Supplier<AgentMetrics> runPrimary,
            Supplier<Boolean> shouldRunFollowup,
            Supplier<AgentMetrics> runFollowup
    ) {}

    public record PipelineResult(
            String kind,
            AgentMetrics primaryMetrics,
            AgentMetrics followupMetrics,
            boolean followupTriggered
    ) {}

    public static List<SettledResult<PipelineResult>> runParallelPipelines(
            PipelineState state,
            List<PipelineConfig> configs,
            Predicate<String> shouldSkip,
            int maxConcurrent,
            ExecutorService executor
    ) {
        List<Supplier<CompletableFuture<PipelineResult>>> thunks = new ArrayList<>();
        for (PipelineConfig cfg : configs) {
            if (shouldSkip.test(cfg.primaryAgent()) && shouldSkip.test(cfg.followupAgent())) continue;
            thunks.add(() -> CompletableFuture.supplyAsync(() -> runOnePipeline(cfg, shouldSkip), executor));
        }

        List<SettledResult<PipelineResult>> results = runWithConcurrencyLimit(thunks, maxConcurrent);

        // Aggregate into state — completion order, key on .value.kind
        for (SettledResult<PipelineResult> r : results) {
            if (r instanceof SettledOk<PipelineResult> ok && ok.value() != null) {
                PipelineResult v = ok.value();
                String primary = v.kind() + "-primary";
                String followup = v.kind() + "-followup";
                if (v.primaryMetrics() != null) {
                    state.agentMetrics.put(primary, v.primaryMetrics());
                    state.completedAgents.add(primary);
                }
                if (v.followupMetrics() != null) {
                    state.agentMetrics.put(followup, v.followupMetrics());
                    state.completedAgents.add(followup);
                }
            }
        }
        return results;
    }

    private static PipelineResult runOnePipeline(PipelineConfig cfg, Predicate<String> shouldSkip) {
        AgentMetrics primaryMetrics = null;
        if (!shouldSkip.test(cfg.primaryAgent())) {
            primaryMetrics = cfg.runPrimary().get();
        }
        boolean triggered = cfg.shouldRunFollowup().get();
        AgentMetrics followupMetrics = null;
        if (triggered && !shouldSkip.test(cfg.followupAgent())) {
            followupMetrics = cfg.runFollowup().get();
        }
        return new PipelineResult(cfg.kind(), primaryMetrics, followupMetrics, triggered);
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private static Throwable unwrap(Throwable t) {
        while (t.getCause() != null && (t instanceof java.util.concurrent.CompletionException
                || t instanceof java.util.concurrent.ExecutionException
                || t instanceof RuntimeException && t.getMessage() == null)) {
            t = t.getCause();
        }
        return t;
    }

    private static void sleepUninterruptibly(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void sneakyThrow(Throwable t) throws T {
        throw (T) t;
    }

    private AgentOrchestrator() {}
}
