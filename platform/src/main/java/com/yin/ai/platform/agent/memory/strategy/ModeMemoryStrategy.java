package com.yin.ai.platform.agent.memory.strategy;

import com.yin.ai.platform.core.agent.mode.AgentMode;
import com.yin.ai.platform.core.memory.MemoryType;

import java.util.Set;

/**
 * 每种 AgentMode 的记忆行为定义。
 * 由 AgentMemoryBridge V2 在生命周期各阶段查询。
 *
 * @param mode                 对应模式
 * @param recallTypes          Pre-Task: 检索哪些记忆类型
 * @param recallTopK           Pre-Task: 检索数量
 * @param workingMemoryEnabled During: 是否创建 WorkingMemory
 * @param episodicPerStep      During: 每步是否追加 Episodic
 * @param consolidation        Post: 整合策略
 */
public record ModeMemoryStrategy(
        AgentMode mode,
        Set<MemoryType> recallTypes,
        int recallTopK,
        boolean workingMemoryEnabled,
        boolean episodicPerStep,
        ConsolidationPolicy consolidation
) {
    /** Post-Task 整合策略 */
    public record ConsolidationPolicy(
            boolean extractSemantic,
            boolean extractProcedural,
            int minStepsToTrigger,
            double confidenceThreshold
    ) {
        public static ConsolidationPolicy none() {
            return new ConsolidationPolicy(false, false, Integer.MAX_VALUE, 1.0);
        }

        public static ConsolidationPolicy episodicOnly() {
            return new ConsolidationPolicy(false, false, 1, 1.0);
        }

        public static ConsolidationPolicy full(int minSteps) {
            return new ConsolidationPolicy(true, true, minSteps, 0.7);
        }
    }

    // —— 预置策略 ——

    public static ModeMemoryStrategy simple() {
        return new ModeMemoryStrategy(AgentMode.DIRECT,
                Set.of(MemoryType.SESSION), 5, false, false,
                ConsolidationPolicy.none());
    }

    public static ModeMemoryStrategy react() {
        return new ModeMemoryStrategy(AgentMode.REACT,
                Set.of(MemoryType.SESSION, MemoryType.SEMANTIC), 8, true, true,
                ConsolidationPolicy.episodicOnly());
    }

    public static ModeMemoryStrategy planExecute() {
        return new ModeMemoryStrategy(AgentMode.PLAN_EXECUTE,
                Set.of(MemoryType.SEMANTIC, MemoryType.PROCEDURAL), 12, true, true,
                ConsolidationPolicy.full(3));
    }

    public static ModeMemoryStrategy deepResearch() {
        return new ModeMemoryStrategy(AgentMode.DEEP_RESEARCH,
                Set.of(MemoryType.SEMANTIC), 15, true, true,
                ConsolidationPolicy.full(5));
    }
}
