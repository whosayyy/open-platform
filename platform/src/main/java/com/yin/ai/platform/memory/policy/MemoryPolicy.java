package com.yin.ai.platform.memory.policy;

/**
 * Memory 策略聚合 — 将四种策略打包为一个配置单元。
 * <p>
 * 每个 MemoryId 可以关联一个 MemoryPolicy，在 append 操作后由
 * {@link MemoryPolicyEngine} 评估是否需要触发后台维护任务。
 *
 * @param retention    保留策略
 * @param compression  压缩策略
 * @param consolidation Consolidation 策略
 * @param eviction     淘汰策略
 */
public record MemoryPolicy(
        RetentionPolicy retention,
        CompressionPolicy compression,
        ConsolidationPolicy consolidation,
        EvictionPolicy eviction) {

    public MemoryPolicy {
        retention = retention != null ? retention : RetentionPolicy.defaults();
        compression = compression != null ? compression : CompressionPolicy.disabled();
        consolidation = consolidation != null ? consolidation : ConsolidationPolicy.defaults();
        eviction = eviction != null ? eviction : EvictionPolicy.defaults();
    }

    public static MemoryPolicy defaults() {
        return new MemoryPolicy(null, null, null, null);
    }
}
