package com.yin.ai.platform.memory.orchestration;

import com.yin.ai.platform.core.memory.api.ScoredMemoryCell;

import java.util.List;
import java.util.Map;

/** Final memory fragment safe to inject into an LLM context. */
public record MemoryContextBundle(
        MemoryRetrievalPlan plan, List<ScoredMemoryCell> memories,
        String renderedText, int actualTokens, Map<String, Integer> discardedByReason) {

    public MemoryContextBundle {
        memories = memories != null ? List.copyOf(memories) : List.of();
        renderedText = renderedText != null ? renderedText : "";
        actualTokens = Math.max(0, actualTokens);
        discardedByReason = discardedByReason != null ? Map.copyOf(discardedByReason) : Map.of();
    }

    public static MemoryContextBundle empty(MemoryRetrievalPlan plan) {
        return new MemoryContextBundle(plan, List.of(), "", 0, Map.of());
    }
}
