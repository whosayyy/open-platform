package com.yin.ai.platform.engine.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * AI 服务统一 Metrics 注册中心。
 * <p>
 * 覆盖 Gateway、Router、Runtime、Function 四层的核心指标。
 * 各层通过 Filter/AOP/模板方法自动埋点，不需要业务代码手动调用。
 */
public class AiMetrics {

    private final MeterRegistry meterRegistry;

    // —— Gateway ——
    private final Counter gatewayRequestTotal;
    private final Timer gatewayRequestDuration;
    private final Counter gatewayRetryCount;
    private final Counter gatewayFallbackCount;
    private final Counter gatewayErrorCount;

    // —— Router ——
    private final Counter routerMatchTotal;
    private final Timer routerMatchDuration;
    private final Counter routerDefaultMatchCount;
    private final Counter routerCacheHitTotal;
    private final Counter routerCacheMissTotal;
    private final ConcurrentHashMap<String, Timer> matcherDurationTimers = new ConcurrentHashMap<>();

    // —— Runtime ——
    private final Counter runtimeExecutionTotal;
    private final Timer runtimeExecutionDuration;
    private final Counter runtimeToolCallTotal;

    // —— RAG ——
    private final Counter ragRetrieveTotal;
    private final Timer ragRetrieveDuration;
    private final Counter ragDocumentRetrievedCount;

    // —— Function ——
    private final Counter functionExecutionTotal;
    private final Timer functionExecutionDuration;
    private final Counter functionErrorCount;

    // —— Agent ——
    private final Timer agentTaskDuration;
    private final DistributionSummary agentTaskSteps;
    private final Counter agentToolCallCount;
    private final DistributionSummary agentQuotaUsage;

    public AiMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;

        this.gatewayRequestTotal = Counter.builder("ai.gateway.request.total")
                .description("Total AI Gateway requests")
                .register(meterRegistry);
        this.gatewayRequestDuration = Timer.builder("ai.gateway.request.duration")
                .description("AI Gateway request duration")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meterRegistry);
        this.gatewayRetryCount = Counter.builder("ai.gateway.retry.count")
                .description("AI Gateway retry count")
                .register(meterRegistry);
        this.gatewayFallbackCount = Counter.builder("ai.gateway.fallback.count")
                .description("AI Gateway fallback count")
                .register(meterRegistry);
        this.gatewayErrorCount = Counter.builder("ai.gateway.error.count")
                .description("AI Gateway error count")
                .register(meterRegistry);

        this.routerMatchTotal = Counter.builder("ai.router.match.total")
                .description("Total router matches")
                .register(meterRegistry);
        this.routerMatchDuration = Timer.builder("ai.router.match.duration")
                .description("Router match duration")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meterRegistry);
        this.routerDefaultMatchCount = Counter.builder("ai.router.match.default")
                .description("Router default (fallback) match count")
                .register(meterRegistry);
        this.routerCacheHitTotal = Counter.builder("ai.router.cache.hit")
                .description("Intent cache hit count")
                .register(meterRegistry);
        this.routerCacheMissTotal = Counter.builder("ai.router.cache.miss")
                .description("Intent cache miss count")
                .register(meterRegistry);

        this.runtimeExecutionTotal = Counter.builder("ai.runtime.execution.total")
                .description("Total Skill executions")
                .register(meterRegistry);
        this.runtimeExecutionDuration = Timer.builder("ai.runtime.execution.duration")
                .description("Skill execution duration")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meterRegistry);
        this.runtimeToolCallTotal = Counter.builder("ai.runtime.tool_call.total")
                .description("Total tool call count")
                .register(meterRegistry);

        this.ragRetrieveTotal = Counter.builder("ai.rag.retrieve.total")
                .description("Total RAG retrieval count")
                .register(meterRegistry);
        this.ragRetrieveDuration = Timer.builder("ai.rag.retrieve.duration")
                .description("RAG retrieval duration")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meterRegistry);
        this.ragDocumentRetrievedCount = Counter.builder("ai.rag.documents.retrieved")
                .description("Total documents retrieved via RAG")
                .register(meterRegistry);

        this.functionExecutionTotal = Counter.builder("ai.function.execution.total")
                .description("Total Function executions")
                .register(meterRegistry);
        this.functionExecutionDuration = Timer.builder("ai.function.execution.duration")
                .description("Function execution duration")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meterRegistry);
        this.functionErrorCount = Counter.builder("ai.function.execution.error")
                .description("Function execution errors")
                .register(meterRegistry);

        this.agentTaskDuration = Timer.builder("ai.agent.task.duration")
                .description("Agent task execution duration")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meterRegistry);
        this.agentTaskSteps = DistributionSummary.builder("ai.agent.task.steps")
                .description("Agent task execution steps")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meterRegistry);
        this.agentToolCallCount = Counter.builder("ai.agent.tool_call.count")
                .description("Total agent tool call count")
                .register(meterRegistry);
        this.agentQuotaUsage = DistributionSummary.builder("ai.agent.quota.usage")
                .description("Agent quota usage distribution across users")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meterRegistry);
    }

    // ——— Gateway ———

    public void recordGatewayRequest(String caller, String model, String status, long durationMs) {
        gatewayRequestTotal.increment();
        gatewayRequestDuration.record(durationMs, TimeUnit.MILLISECONDS);
    }

    public void recordGatewayRetry() {
        gatewayRetryCount.increment();
    }

    public void recordGatewayFallback() {
        gatewayFallbackCount.increment();
    }

    public void recordGatewayError() {
        gatewayErrorCount.increment();
    }

    // ——— Router ———

    public void recordRouterMatch(long durationMicros, boolean isDefault) {
        routerMatchTotal.increment();
        routerMatchDuration.record(durationMicros, TimeUnit.MICROSECONDS);
        if (isDefault) {
            routerDefaultMatchCount.increment();
        }
    }

    /** 记录单个 Matcher 命中（按 matcherName 分维度）。Micrometer 内部缓存同名 Counter。 */
    public void recordMatcherHit(String matcherName) {
        meterRegistry.counter("ai.router.matcher.hit", "matcher", matcherName).increment();
    }

    /** 记录单个 Matcher 耗时。Timer 需 percentile 配置故缓存，避免重复 register 抛异常。 */
    public void recordMatcherDuration(String matcherName, long durationMicros) {
        matcherDurationTimers
                .computeIfAbsent(matcherName, name ->
                        Timer.builder("ai.router.matcher.duration")
                                .description("Router matcher duration by matcher")
                                .tag("matcher", name)
                                .publishPercentiles(0.5, 0.95, 0.99)
                                .register(meterRegistry))
                .record(durationMicros, TimeUnit.MICROSECONDS);
    }

    /** 记录最终意图分布（按 intent 名字分维度）。Micrometer 内部缓存同名 Counter。 */
    public void recordIntentDistribution(String intentName) {
        meterRegistry.counter("ai.router.intent", "intent", intentName).increment();
    }

    /** 记录缓存命中。 */
    public void recordCacheHit() {
        routerCacheHitTotal.increment();
    }

    /** 记录缓存未命中。 */
    public void recordCacheMiss() {
        routerCacheMissTotal.increment();
    }

    // ——— Runtime ———

    public void recordRuntimeExecution(String skillName, String status, long durationMs) {
        runtimeExecutionTotal.increment();
        runtimeExecutionDuration.record(durationMs, TimeUnit.MILLISECONDS);
    }

    public void recordRuntimeToolCall() {
        runtimeToolCallTotal.increment();
    }

    // ——— RAG ———

    public void recordRAGRetrieve(long durationMs) {
        ragRetrieveTotal.increment();
        ragRetrieveDuration.record(durationMs, TimeUnit.MILLISECONDS);
    }

    public void recordRAGDocumentsRetrieved(int count) {
        ragDocumentRetrievedCount.increment(count);
    }

    // ——— Function ———

    public void recordFunctionExecution(String functionName, String status, long durationMs) {
        functionExecutionTotal.increment();
        functionExecutionDuration.record(durationMs, TimeUnit.MILLISECONDS);
        if ("error".equals(status)) {
            functionErrorCount.increment();
        }
    }

    // ——— Agent ———

    public void recordAgentTaskDuration(long durationMs) {
        agentTaskDuration.record(durationMs, TimeUnit.MILLISECONDS);
    }

    public void recordAgentTaskSteps(int steps) {
        agentTaskSteps.record(steps);
    }

    public void recordAgentToolCall() {
        agentToolCallCount.increment();
    }

    public void recordQuotaUsage(String userId, int used) {
        agentQuotaUsage.record(used);
        // Never put user identity into metric labels; userId remains a compatibility argument only.
        meterRegistry.counter("ai.agent.quota.usage.count", "scope", "user").increment();
    }
}
