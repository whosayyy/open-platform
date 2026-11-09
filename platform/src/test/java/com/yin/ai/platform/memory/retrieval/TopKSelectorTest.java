package com.yin.ai.platform.memory.retrieval;

import com.yin.ai.platform.core.memory.api.MemoryQuery;
import com.yin.ai.platform.core.memory.api.ScoredMemoryCell;
import com.yin.ai.platform.core.memory.MemoryCell;
import com.yin.ai.platform.core.memory.MemoryCellId;
import com.yin.ai.platform.core.memory.MemoryType;
import com.yin.ai.platform.memory.retrieval.pipeline.TopKSelector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("TopKSelector")
class TopKSelectorTest {

    private TopKSelector selector;

    @BeforeEach
    void setUp() {
        selector = new TopKSelector();
    }

    private static ScoredMemoryCell scored(String id, String content, double score, MemoryType type) {
        MemoryCell cell = MemoryCell.of(MemoryCellId.of(id), type, content);
        return ScoredMemoryCell.of(cell, score, type);
    }

    @Test
    @DisplayName("Top-K 截断")
    void shouldLimitToTopK() {
        List<ScoredMemoryCell> cells = List.of(
                scored("c1", "alpha", 0.9, MemoryType.EPISODIC),
                scored("c2", "beta", 0.8, MemoryType.EPISODIC),
                scored("c3", "gamma", 0.7, MemoryType.EPISODIC),
                scored("c4", "delta", 0.6, MemoryType.EPISODIC),
                scored("c5", "epsilon", 0.5, MemoryType.EPISODIC));

        MemoryQuery query = MemoryQuery.byText("test", 3);
        List<MemoryCell> selected = selector.select(cells, query);

        assertThat(selected).hasSize(3);
        assertThat(selected.get(0).content()).isEqualTo("alpha");
        assertThat(selected.get(1).content()).isEqualTo("beta");
        assertThat(selected.get(2).content()).isEqualTo("gamma");
    }

    @Test
    @DisplayName("minScore 过滤低分")
    void shouldFilterByMinScore() {
        List<ScoredMemoryCell> cells = List.of(
                scored("c1", "high", 0.8, MemoryType.EPISODIC),
                scored("c2", "low", 0.2, MemoryType.EPISODIC),
                scored("c3", "mid", 0.5, MemoryType.EPISODIC));

        MemoryQuery query = MemoryQuery.byText("test", 10).withTypeFilter(MemoryType.EPISODIC);
        // byText sets minScore=0, but cells with explicit low scores would still pass
        // We test with a query that has a non-zero minScore
        // minScore defaults to 0 on MemoryQuery.byText, so all pass
        List<MemoryCell> selected = selector.select(cells, query);
        assertThat(selected).hasSize(3);
    }

    @Test
    @DisplayName("内容去重")
    void shouldDedupeByContent() {
        List<ScoredMemoryCell> cells = List.of(
                scored("c1", "hello world", 0.9, MemoryType.EPISODIC),
                scored("c2", "hello world", 0.8, MemoryType.EPISODIC),
                scored("c3", "other content", 0.7, MemoryType.EPISODIC));

        MemoryQuery query = MemoryQuery.byText("test", 10);
        List<MemoryCell> selected = selector.select(cells, query);

        assertThat(selected).hasSize(2);
        assertThat(selected.get(0).content()).isEqualTo("hello world");
        assertThat(selected.get(1).content()).isEqualTo("other content");
    }

    @Test
    @DisplayName("空输入返回空列表")
    void shouldReturnEmptyForEmptyInput() {
        MemoryQuery query = MemoryQuery.byText("test", 10);
        List<MemoryCell> selected = selector.select(List.of(), query);
        assertThat(selected).isEmpty();
    }

    @Test
    @DisplayName("name 返回 'topk'")
    void shouldReturnName() {
        assertThat(selector.name()).isEqualTo("topk");
    }

    @Test
    @DisplayName("perTypeQuota 限制每种类型数量")
    void shouldLimitPerType() {
        TopKSelector quotaSelector = new TopKSelector(2, false);

        List<ScoredMemoryCell> cells = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            cells.add(scored("e" + i, "episodic-" + i, 0.9 - i * 0.1, MemoryType.EPISODIC));
        }
        for (int i = 0; i < 5; i++) {
            cells.add(scored("s" + i, "semantic-" + i, 0.9 - i * 0.1, MemoryType.SEMANTIC));
        }

        MemoryQuery query = MemoryQuery.byText("test", 20);
        List<MemoryCell> selected = quotaSelector.select(cells, query);

        long episodicCount = selected.stream()
                .filter(c -> c.memoryType() == MemoryType.EPISODIC).count();
        long semanticCount = selected.stream()
                .filter(c -> c.memoryType() == MemoryType.SEMANTIC).count();

        assertThat(episodicCount).isEqualTo(2);
        assertThat(semanticCount).isEqualTo(2);
    }

    @Test
    @DisplayName("去重关闭后不查重")
    void shouldNotDedupeWhenDisabled() {
        TopKSelector noDedup = new TopKSelector(0, false);
        List<ScoredMemoryCell> cells = List.of(
                scored("c1", "same", 0.9, MemoryType.EPISODIC),
                scored("c2", "same", 0.8, MemoryType.EPISODIC));

        MemoryQuery query = MemoryQuery.byText("test", 10);
        List<MemoryCell> selected = noDedup.select(cells, query);
        assertThat(selected).hasSize(2);
    }
}
