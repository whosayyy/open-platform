package com.yin.ai.platform.memory.orchestration;

import com.yin.ai.platform.core.memory.MemoryType;
import com.yin.ai.platform.core.memory.security.MemoryScope;

import java.util.Set;

/** Scope-aware bounded retrieval plan generated before any store query. */
public record MemoryRetrievalPlan(
        Set<MemoryType> memoryTypes, Set<MemoryScope> scopes,
        int maxItems, int tokenBudget, double minScore,
        boolean temporalFilterEnabled, boolean rerankEnabled) {

    public MemoryRetrievalPlan {
        memoryTypes = memoryTypes != null ? Set.copyOf(memoryTypes) : Set.of();
        scopes = scopes != null ? Set.copyOf(scopes) : Set.of();
        maxItems = maxItems > 0 ? maxItems : 10;
        tokenBudget = Math.max(0, tokenBudget);
        minScore = Math.clamp(minScore, 0.0, 1.0);
    }
}
