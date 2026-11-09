package com.yin.ai.platform.memory.retrieval.ranking;

import com.yin.ai.platform.core.memory.api.MemoryQuery;
import com.yin.ai.platform.core.memory.api.ScoredMemoryCell;
import com.yin.ai.platform.core.memory.MemoryCell;
import com.yin.ai.platform.memory.retrieval.RelevanceRanker;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 组合排序器 — 按权重融合多个 RelevanceRanker 的评分。
 * <p>
 * finalScore = Σ (weight_i × normalize(ranker_i.score(cell, query)))
 * <p>
 * 使用场景：
 * <ul>
 *   <li>语义匹配 (weight=0.5) + 时间衰减 (weight=0.3) + 标签匹配 (weight=0.2)</li>
 *   <li>写入侧评分（用户反馈/采纳次数）+ 读取侧评分（相关性）加权融合</li>
 * </ul>
 * <p>
 * 权重总和不必等于 1.0——内部自动归一化。
 */
public class CompositeRanker implements RelevanceRanker {

    private final List<WeightedRanker> rankers;
    private final double totalWeight;

    public CompositeRanker(List<WeightedRanker> rankers) {
        this.rankers = List.copyOf(rankers);
        this.totalWeight = rankers.stream().mapToDouble(WeightedRanker::weight).sum();
    }

    @Override
    public double score(MemoryCell cell, MemoryQuery query) {
        if (rankers.isEmpty()) return 0.0;
        double sum = 0.0;
        for (WeightedRanker wr : rankers) {
            double raw = wr.ranker().score(cell, query);
            sum += wr.weight() * raw;
        }
        return totalWeight > 0 ? sum / totalWeight : sum;
    }

    @Override
    public List<ScoredMemoryCell> rank(List<ScoredMemoryCell> cells, int topK) {
        return cells.stream()
                .map(sc -> {
                    double newScore = score(sc.cell(), null);
                    return new ScoredMemoryCell(sc.cell(), newScore, sc.sourceType(), sc.explanation());
                })
                .sorted(Comparator.comparingDouble(ScoredMemoryCell::score).reversed())
                .limit(topK > 0 ? topK : 10)
                .toList();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private final List<WeightedRanker> rankers = new ArrayList<>();

        public Builder add(RelevanceRanker ranker, double weight) {
            rankers.add(new WeightedRanker(ranker, weight));
            return this;
        }

        public CompositeRanker build() {
            return new CompositeRanker(rankers);
        }
    }

    /**
     * 加权排序器对。
     */
    public record WeightedRanker(RelevanceRanker ranker, double weight) {
        public WeightedRanker {
            if (weight < 0) throw new IllegalArgumentException("weight must be >= 0");
        }
    }
}
