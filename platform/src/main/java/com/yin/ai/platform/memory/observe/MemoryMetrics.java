package com.yin.ai.platform.memory.observe;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.util.concurrent.TimeUnit;

/**
 * Memory 模块 Micrometer 指标收集器。
 * <p>
 * 覆盖 Memory Runtime 核心操作：
 * <ul>
 *   <li>Append / Read 计数和耗时</li>
 *   <li>L1 缓存命中/未命中</li>
 *   <li>CAS 冲突计数</li>
 *   <li>Store 健康状态</li>
 *   <li>各 MemoryType 分布</li>
 * </ul>
 * <p>
 * 遵循 {@link com.yin.ai.platform.observe.AiMetrics} 模式，
 * 使用 Micrometer Counter + Timer，支持 Prometheus / InfluxDB 等后端。
 */
public class MemoryMetrics {

    private final MeterRegistry meterRegistry;

    private final Counter appendTotal;
    private final Timer appendDuration;
    private final Counter readTotal;
    private final Timer readDuration;
    private final Counter cacheHitTotal;
    private final Counter cacheMissTotal;
    private final Counter casConflictTotal;
    private final Counter storeErrorTotal;
    private final Counter eventPublishedTotal;

    public MemoryMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;

        this.appendTotal = Counter.builder("ai.memory.append.total")
                .description("Total Memory append operations")
                .register(meterRegistry);
        this.appendDuration = Timer.builder("ai.memory.append.duration")
                .description("Memory append duration")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meterRegistry);
        this.readTotal = Counter.builder("ai.memory.read.total")
                .description("Total Memory read operations")
                .register(meterRegistry);
        this.readDuration = Timer.builder("ai.memory.read.duration")
                .description("Memory read duration")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meterRegistry);
        this.cacheHitTotal = Counter.builder("ai.memory.cache.hit")
                .description("L1 cache hit count")
                .register(meterRegistry);
        this.cacheMissTotal = Counter.builder("ai.memory.cache.miss")
                .description("L1 cache miss count")
                .register(meterRegistry);
        this.casConflictTotal = Counter.builder("ai.memory.cas.conflict")
                .description("CAS optimistic lock conflict count")
                .register(meterRegistry);
        this.storeErrorTotal = Counter.builder("ai.memory.store.error")
                .description("Memory store error count")
                .register(meterRegistry);
        this.eventPublishedTotal = Counter.builder("ai.memory.event.published")
                .description("Memory event published count")
                .register(meterRegistry);
    }

    // ==================== Recording ====================

    public void recordAppend(String memoryType, long durationMs) {
        appendTotal.increment();
        appendDuration.record(durationMs, TimeUnit.MILLISECONDS);
        meterRegistry.counter("ai.memory.append.type", "type", memoryType).increment();
    }

    public void recordRead(String memoryType, long durationMs) {
        readTotal.increment();
        readDuration.record(durationMs, TimeUnit.MILLISECONDS);
    }

    public void recordCacheHit() {
        cacheHitTotal.increment();
    }

    public void recordCacheMiss() {
        cacheMissTotal.increment();
    }

    public void recordCasConflict() {
        casConflictTotal.increment();
    }

    public void recordStoreError(String storeType) {
        storeErrorTotal.increment();
        meterRegistry.counter("ai.memory.store.error", "store", storeType).increment();
    }

    public void recordEventPublished(String eventType) {
        eventPublishedTotal.increment();
        meterRegistry.counter("ai.memory.event.type", "event", eventType).increment();
    }

    public void recordRemember(String type, String scope, String outcome) {
        meterRegistry.counter("memory_remember_total", "type", low(type),
                "scope", low(scope), "outcome", low(outcome)).increment();
    }

    public void recordRetrieval(String outcome, int selected, int tokens, long durationMs) {
        meterRegistry.counter("memory_retrieval_total", "outcome", low(outcome)).increment();
        meterRegistry.summary("memory_retrieval_items", "outcome", low(outcome)).record(selected);
        meterRegistry.summary("memory_context_tokens", "outcome", low(outcome)).record(tokens);
        meterRegistry.timer("memory_retrieval_latency", "outcome", low(outcome))
                .record(durationMs, TimeUnit.MILLISECONDS);
    }

    public void recordConflict(String relation, String outcome) {
        meterRegistry.counter("memory_conflict_total", "relation", low(relation),
                "outcome", low(outcome)).increment();
    }

    public void recordConsolidation(String outcome, int promoted) {
        meterRegistry.counter("memory_consolidation_total", "outcome", low(outcome)).increment();
        meterRegistry.summary("memory_consolidation_promoted", "outcome", low(outcome))
                .record(promoted);
    }

    public void recordForget(String outcome, String store, int count) {
        meterRegistry.counter("memory_forget_total", "outcome", low(outcome),
                "store", low(store)).increment(Math.max(1, count));
    }

    public void recordOutbox(String outcome) {
        meterRegistry.counter("memory_outbox_total", "outcome", low(outcome)).increment();
    }

    public void recordLlmFallback(String operation, String outcome) {
        meterRegistry.counter("memory_llm_fallback_total", "operation", low(operation),
                "outcome", low(outcome)).increment();
    }

    private String low(String value) {
        return value == null || value.isBlank() ? "unknown" : value.toLowerCase(java.util.Locale.ROOT);
    }

    /** 记录当前活跃 Memory 总数（Gauge） */
    public void recordActiveMemoryCount(long count) {
        meterRegistry.gauge("ai.memory.active.count", count);
    }

    /** 记录总 Cell 数（Gauge） */
    public void recordTotalCellCount(long count) {
        meterRegistry.gauge("ai.memory.cell.count", count);
    }

    public MeterRegistry meterRegistry() {
        return meterRegistry;
    }
}
