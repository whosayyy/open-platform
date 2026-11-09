package com.yin.ai.platform.memory.retrieval;

import com.yin.ai.platform.core.memory.api.MemoryQuery;
import com.yin.ai.platform.core.memory.api.ScoredMemoryCell;
import com.yin.ai.platform.core.memory.MemoryCell;
import com.yin.ai.platform.core.memory.MemoryCellId;
import com.yin.ai.platform.core.memory.MemoryType;
import com.yin.ai.platform.memory.retrieval.ranking.CompositeRanker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CompositeRanker")
class CompositeRankerTest {

    @Test
    @DisplayName("空 ranker 列表返回 0.0")
    void shouldReturnZeroForEmpty() {
        CompositeRanker ranker = new CompositeRanker(List.of());
        MemoryCell cell = MemoryCell.of(MemoryCellId.of("c1"), MemoryType.EPISODIC, "test");
        assertThat(ranker.score(cell, MemoryQuery.byText("q", 10))).isZero();
    }

    @Test
    @DisplayName("加权融合多个 ranker")
    void shouldCombineRankers() {
        // ranker1 总是返回 1.0, weight=0.7
        // ranker2 总是返回 0.0, weight=0.3
        // 期望: (0.7*1.0 + 0.3*0.0) / 1.0 = 0.7
        RelevanceRanker alwaysHigh = (cell, query) -> 1.0;
        RelevanceRanker alwaysLow = (cell, query) -> 0.0;

        CompositeRanker ranker = CompositeRanker.builder()
                .add(alwaysHigh, 0.7)
                .add(alwaysLow, 0.3)
                .build();

        MemoryCell cell = MemoryCell.of(MemoryCellId.of("c1"), MemoryType.EPISODIC, "test");
        double score = ranker.score(cell, MemoryQuery.byText("q", 10));
        assertThat(score).isCloseTo(0.7, org.assertj.core.data.Offset.offset(0.001));
    }

    @Test
    @DisplayName("权重自动归一化")
    void shouldNormalizeWeights() {
        RelevanceRanker r1 = (cell, query) -> 0.5;
        RelevanceRanker r2 = (cell, query) -> 0.8;

        // 权重和 = 4.0 (不是 1.0)，自动归一化
        CompositeRanker ranker = CompositeRanker.builder()
                .add(r1, 2.0)
                .add(r2, 2.0)
                .build();

        MemoryCell cell = MemoryCell.of(MemoryCellId.of("c1"), MemoryType.EPISODIC, "test");
        // 期望: (2.0*0.5 + 2.0*0.8) / 4.0 = 0.65
        double score = ranker.score(cell, MemoryQuery.byText("q", 10));
        assertThat(score).isCloseTo(0.65, org.assertj.core.data.Offset.offset(0.001));
    }

    @Test
    @DisplayName("rank 方法批量排序")
    void shouldRankBatch() {
        RelevanceRanker fixed = (cell, query) -> cell.content().equals("high") ? 1.0 : 0.1;

        CompositeRanker ranker = CompositeRanker.builder()
                .add(fixed, 1.0)
                .build();

        MemoryCell c1 = MemoryCell.of(MemoryCellId.of("c1"), MemoryType.EPISODIC, "low");
        MemoryCell c2 = MemoryCell.of(MemoryCellId.of("c2"), MemoryType.EPISODIC, "high");

        ScoredMemoryCell sc1 = ScoredMemoryCell.of(c1, 0.5, MemoryType.EPISODIC);
        ScoredMemoryCell sc2 = ScoredMemoryCell.of(c2, 0.5, MemoryType.EPISODIC);

        List<ScoredMemoryCell> ranked = ranker.rank(List.of(sc1, sc2), 5);
        assertThat(ranked).hasSize(2);
        assertThat(ranked.get(0).cell().content()).isEqualTo("high");
    }

    @Test
    @DisplayName("WeightedRanker 权重不能为负")
    void shouldRejectNegativeWeight() {
        RelevanceRanker dummy = (cell, query) -> 0.5;
        assertThat(org.assertj.core.api.Assertions.catchThrowableOfType(
                () -> new CompositeRanker.WeightedRanker(dummy, -1.0),
                IllegalArgumentException.class)).isNotNull();
    }

    @Test
    @DisplayName("topK 截断")
    void shouldLimitTopK() {
        RelevanceRanker fixed = (cell, query) -> 0.5;
        CompositeRanker ranker = CompositeRanker.builder().add(fixed, 1.0).build();

        List<ScoredMemoryCell> cells = new java.util.ArrayList<>();
        for (int i = 0; i < 10; i++) {
            MemoryCell c = MemoryCell.of(MemoryCellId.of("c" + i), MemoryType.EPISODIC, "cell-" + i);
            cells.add(ScoredMemoryCell.of(c, 0.9 - i * 0.05, MemoryType.EPISODIC));
        }

        List<ScoredMemoryCell> ranked = ranker.rank(cells, 3);
        assertThat(ranked).hasSize(3);
    }
}
