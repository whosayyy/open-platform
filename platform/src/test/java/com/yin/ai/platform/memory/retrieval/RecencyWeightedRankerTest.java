package com.yin.ai.platform.memory.retrieval;

import com.yin.ai.platform.core.memory.api.MemoryQuery;
import com.yin.ai.platform.core.memory.api.ScoredMemoryCell;
import com.yin.ai.platform.core.memory.MemoryCell;
import com.yin.ai.platform.core.memory.MemoryCellId;
import com.yin.ai.platform.core.memory.MemoryType;
import com.yin.ai.platform.memory.retrieval.ranking.RecencyWeightedRanker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("RecencyWeightedRanker")
class RecencyWeightedRankerTest {

    @Test
    @DisplayName("刚创建 Cell 权重接近 1.0")
    void shouldGiveHighWeightToRecentCell() {
        RecencyWeightedRanker ranker = new RecencyWeightedRanker(24, 0.3);
        MemoryCell cell = MemoryCell.of(
                MemoryCellId.of("c1"), MemoryType.EPISODIC, "recent");

        double score = ranker.score(cell, null);
        assertThat(score).isGreaterThan(0.9);
    }

    @Test
    @DisplayName("旧 Cell 权重衰减趋于 baseWeight")
    void shouldDecayOldCell() {
        RecencyWeightedRanker ranker = new RecencyWeightedRanker(1, 0.3); // 1 小时半衰
        // Cell 来自 10 小时前
        MemoryCell cell = new MemoryCell(
                MemoryCellId.of("c1"), MemoryType.EPISODIC, "old", null,
                Instant.now().minus(10, ChronoUnit.HOURS), 0, java.util.Optional.empty(),
                null, null, 1);

        double score = ranker.score(cell, null);
        // 10 小时后权重应接近 baseWeight (0.3)
        assertThat(score).isCloseTo(0.3, org.assertj.core.data.Offset.offset(0.05));
    }

    @Test
    @DisplayName("rank 批量排序 + 时间衰减")
    void shouldRankWithRecencyDecay() {
        RecencyWeightedRanker ranker = new RecencyWeightedRanker(24, 0.3);

        MemoryCell recent = MemoryCell.of(MemoryCellId.of("c1"), MemoryType.EPISODIC, "recent");
        MemoryCell old = new MemoryCell(
                MemoryCellId.of("c2"), MemoryType.EPISODIC, "old", null,
                Instant.now().minus(48, ChronoUnit.HOURS), 1, java.util.Optional.empty(),
                null, null, 1);

        ScoredMemoryCell sc1 = ScoredMemoryCell.of(recent, 0.8, MemoryType.EPISODIC);
        ScoredMemoryCell sc2 = ScoredMemoryCell.of(old, 0.8, MemoryType.EPISODIC);

        List<ScoredMemoryCell> ranked = ranker.rank(List.of(sc1, sc2), 5);
        assertThat(ranked).hasSize(2);
        // recent 应该排在 old 前面（衰减后得分更高）
        assertThat(ranked.get(0).cell().content()).isEqualTo("recent");
        assertThat(ranked.get(1).cell().content()).isEqualTo("old");
    }

    @Test
    @DisplayName("halfLifeHours <= 0 抛出异常")
    void shouldRejectInvalidHalfLife() {
        assertThatThrownBy(() -> new RecencyWeightedRanker(0, 0.3))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RecencyWeightedRanker(-1, 0.3))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("baseWeight 越界抛出异常")
    void shouldRejectInvalidBaseWeight() {
        assertThatThrownBy(() -> new RecencyWeightedRanker(24, -0.1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RecencyWeightedRanker(24, 1.1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("默认参数构建")
    void shouldUseDefaultParams() {
        RecencyWeightedRanker ranker = new RecencyWeightedRanker();
        MemoryCell cell = MemoryCell.of(MemoryCellId.of("c1"), MemoryType.EPISODIC, "test");
        double score = ranker.score(cell, null);
        assertThat(score).isBetween(0.0, 1.0);
    }

    @Test
    @DisplayName("半衰期后权重约为 0.65 (= 0.7*0.5 + 0.3)")
    void shouldApproximateHalfLife() {
        double halfLife = 24;
        double baseWeight = 0.3;
        RecencyWeightedRanker ranker = new RecencyWeightedRanker(halfLife, baseWeight);

        // 恰好半衰期处的 cell
        MemoryCell cell = new MemoryCell(
                MemoryCellId.of("c1"), MemoryType.EPISODIC, "half-life", null,
                Instant.now().minus((long) halfLife, ChronoUnit.HOURS), 0, java.util.Optional.empty(),
                null, null, 1);

        double score = ranker.score(cell, null);
        // 期望: baseWeight + (1-baseWeight) * 0.5 = 0.3 + 0.7*0.5 = 0.65
        assertThat(score).isCloseTo(0.65, org.assertj.core.data.Offset.offset(0.02));
    }
}
