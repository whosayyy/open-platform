package com.yin.ai.platform.memory.strategy;

/**
 * 记忆类型决策 — 根据任务上下文动态决定应该加载哪些 MemoryType。
 */
public record MemoryTypeDecision(
        boolean loadWorkingMemory,
        boolean loadEpisodic,
        boolean loadSemantic,
        boolean loadProcedural,
        int recallTopK,
        String reason) {

    public static MemoryTypeDecision none() {
        return new MemoryTypeDecision(false, false, false, false, 0, "no memory needed");
    }

    public static MemoryTypeDecision sessionOnly(int recallTopK) {
        return new MemoryTypeDecision(false, false, false, false, recallTopK, "session only");
    }

    public static MemoryTypeDecision moderate(int recallTopK) {
        return new MemoryTypeDecision(true, true, false, false, recallTopK, "moderate task");
    }

    public static MemoryTypeDecision full(int recallTopK) {
        return new MemoryTypeDecision(true, true, true, true, recallTopK, "complex task");
    }
}
