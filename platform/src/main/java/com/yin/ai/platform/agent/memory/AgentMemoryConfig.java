package com.yin.ai.platform.agent.memory;

/**
 * Agent 记忆配置 — 控制 AgentMemoryBridge 中各 MemoryType 的启用/禁用及参数。
 *
 * @deprecated 使用 {@link com.yin.ai.platform.memory.strategy.MemoryStrategySelector}
 *             进行动态记忆策略选择，替代硬编码 boolean 标志。
 *             当前仍作为 fallback 使用（MemoryStrategySelector 不可用时降级）。
 */
@Deprecated
public record AgentMemoryConfig(
        boolean episodicEnabled,
        boolean workingMemoryEnabled,
        boolean semanticSearchEnabled,
        int episodicTimelineLimit,
        int defaultRecallTopK,
        boolean autoConsolidate,
        int resumePointInterval) {

    public static AgentMemoryConfig defaults() {
        return new AgentMemoryConfig(true, true, false, 200, 5, false, 3);
    }
}
