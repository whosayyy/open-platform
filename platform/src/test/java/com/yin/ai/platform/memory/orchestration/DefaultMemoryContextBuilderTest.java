package com.yin.ai.platform.memory.orchestration;

import com.yin.ai.platform.core.memory.api.ScoredMemoryCell;
import com.yin.ai.platform.core.memory.MemoryCell;
import com.yin.ai.platform.core.memory.MemoryCellId;
import com.yin.ai.platform.core.memory.MemoryType;
import com.yin.ai.platform.core.memory.security.MemoryScope;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultMemoryContextBuilderTest {
    @Test
    void neverExceedsTotalOrTypeBudgetAndHasStableOrdering() {
        String longText = "memory ".repeat(200);
        List<ScoredMemoryCell> candidates = List.of(
                scored("b", longText, 0.9, 0.8, 0.9),
                scored("a", "short semantic memory", 0.9, 0.8, 0.9),
                scored("c", "another semantic memory", 0.8, 0.7, 0.8));
        MemoryRetrievalPlan plan = new MemoryRetrievalPlan(Set.of(MemoryType.SEMANTIC),
                Set.of(MemoryScope.USER), 5, 100, 0, true, true);

        MemoryContextBundle bundle = new DefaultMemoryContextBuilder().build(plan, candidates);

        assertThat(bundle.actualTokens()).isLessThanOrEqualTo(100);
        assertThat(bundle.memories()).extracting(value -> value.cell().cellId().id())
                .containsExactly("a", "c");
        assertThat(bundle.discardedByReason()).containsKey("typeBudget");
    }

    private ScoredMemoryCell scored(String id, String content, double relevance,
                                    double importance, double confidence) {
        MemoryCell cell = MemoryCell.of(MemoryCellId.of(id), MemoryType.SEMANTIC, content,
                Map.of("importance", importance, "confidence", confidence));
        return ScoredMemoryCell.of(cell, relevance, MemoryType.SEMANTIC);
    }
}
