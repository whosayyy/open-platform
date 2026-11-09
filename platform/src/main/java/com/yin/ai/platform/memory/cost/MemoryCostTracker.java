package com.yin.ai.platform.memory.cost;

import com.yin.ai.platform.core.memory.MemoryType;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Memory 用量追踪器 — 按租户聚合 Memory 资源消耗。
 * <p>
 * 企业级要求：
 * <ul>
 *   <li><b>Per-tenant 聚合</b>: 每个租户独立统计</li>
 *   <li><b>实时 + 批量</b>: 实时计数 + 定时持久化到 DB</li>
 *   <li><b>多维度</b>: Token / 存储 / API 调用 / 嵌入次数 分别统计</li>
 * </ul>
 * <p>
 * 线程安全：AtomicLong + ConcurrentHashMap。
 */
public class MemoryCostTracker {

    private final Map<String, TenantCost> tenantCosts = new ConcurrentHashMap<>();

    /** 记录一次用量 */
    public void record(MemoryUsageRecord record) {
        String tenantId = record.tenantId() != null ? record.tenantId() : "default";
        TenantCost cost = tenantCosts.computeIfAbsent(tenantId, k -> new TenantCost());
        cost.add(record);
    }

    /** 批量记录 */
    public void recordBatch(List<MemoryUsageRecord> records) {
        for (MemoryUsageRecord r : records) {
            record(r);
        }
    }

    /** 获取指定租户的累计用量 */
    public TenantUsage getUsage(String tenantId) {
        TenantCost cost = tenantCosts.get(tenantId);
        if (cost == null) return TenantUsage.zero();
        return cost.snapshot();
    }

    /** 获取所有租户的用量 */
    public Map<String, TenantUsage> getAllUsage() {
        Map<String, TenantUsage> result = new ConcurrentHashMap<>();
        for (Map.Entry<String, TenantCost> entry : tenantCosts.entrySet()) {
            result.put(entry.getKey(), entry.getValue().snapshot());
        }
        return result;
    }

    /** 重置指定租户的用量（通常在新的计费周期开始前调用） */
    public TenantUsage reset(String tenantId) {
        TenantCost removed = tenantCosts.remove(tenantId);
        return removed != null ? removed.snapshot() : TenantUsage.zero();
    }

    /**
     * 租户累计用量快照。
     */
    public record TenantUsage(
            long totalInputTokens,
            long totalOutputTokens,
            long totalEmbeddingTokens,
            long totalApiCalls,
            long totalStorageBytes,
            long totalCellCount,
            Map<String, Long> operationsByType) {

        public long totalTokens() {
            return totalInputTokens + totalOutputTokens + totalEmbeddingTokens;
        }

        static TenantUsage zero() {
            return new TenantUsage(0, 0, 0, 0, 0, 0, Map.of());
        }
    }

    /** 内部累加器 */
    private static class TenantCost {
        private final AtomicLong inputTokens = new AtomicLong();
        private final AtomicLong outputTokens = new AtomicLong();
        private final AtomicLong embeddingTokens = new AtomicLong();
        private final AtomicLong apiCalls = new AtomicLong();
        private final AtomicLong storageBytes = new AtomicLong();
        private final AtomicLong cellCount = new AtomicLong();
        private final Map<String, AtomicLong> opCounts = new ConcurrentHashMap<>();

        void add(MemoryUsageRecord r) {
            inputTokens.addAndGet(r.inputTokens());
            outputTokens.addAndGet(r.outputTokens());
            apiCalls.incrementAndGet();
            storageBytes.addAndGet(r.storageBytes());
            cellCount.addAndGet(r.cellCount());

            if (r.operationType() == MemoryUsageRecord.OperationType.EMBED) {
                embeddingTokens.addAndGet(r.inputTokens());
            }

            String opKey = r.operationType().name();
            opCounts.computeIfAbsent(opKey, k -> new AtomicLong()).incrementAndGet();
        }

        TenantUsage snapshot() {
            Map<String, Long> ops = new ConcurrentHashMap<>();
            opCounts.forEach((k, v) -> ops.put(k, v.get()));
            return new TenantUsage(
                    inputTokens.get(), outputTokens.get(), embeddingTokens.get(),
                    apiCalls.get(), storageBytes.get(), cellCount.get(), Map.copyOf(ops));
        }
    }
}
