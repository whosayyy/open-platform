package com.yin.ai.platform.memory.retrieval.ranking;

import com.yin.ai.platform.core.memory.api.MemoryQuery;
import com.yin.ai.platform.core.memory.api.ScoredMemoryCell;
import com.yin.ai.platform.core.memory.MemoryCell;
import com.yin.ai.platform.memory.retrieval.RelevanceRanker;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 时间衰减排序器 — 对检索结果施加重现性衰减。
 * <p>
 * 核心公式：finalScore = baseScore × recencyWeight <br>
 * recencyWeight = baseWeight + (1 - baseWeight) × e^(-λ × ageInHours) <br>
 * <p>
 * 参数：
 * <ul>
 *   <li>halfLifeHours: 半衰期（小时后权重降至 0.5），默认 24h</li>
 *   <li>baseWeight: 最旧数据的保底权重，默认 0.3</li>
 * </ul>
 * <p>
 * 效果：
 * <ul>
 *   <li>刚发生的事（0h）: weight ≈ 1.0</li>
 *   <li>一天前的事（24h）: weight ≈ 0.65</li>
 *   <li>一周前的事（168h）: weight ≈ 0.31</li>
 *   <li>极限旧数据: weight → baseWeight (0.3)</li>
 * </ul>
 */
public class RecencyWeightedRanker implements RelevanceRanker {

    private final double halfLifeHours;
    private final double baseWeight;

    public RecencyWeightedRanker() {
        this(24.0, 0.3);
    }

    public RecencyWeightedRanker(double halfLifeHours, double baseWeight) {
        if (halfLifeHours <= 0) throw new IllegalArgumentException("halfLifeHours must be > 0");
        if (baseWeight < 0 || baseWeight > 1) throw new IllegalArgumentException("baseWeight must be in [0, 1]");
        this.halfLifeHours = halfLifeHours;
        this.baseWeight = baseWeight;
    }

    @Override
    public double score(MemoryCell cell, MemoryQuery query) {
        double ageHours = Duration.between(cell.timestamp(), Instant.now()).toHours();
        double lambda = Math.log(2) / halfLifeHours;
        double recencyWeight = baseWeight + (1.0 - baseWeight) * Math.exp(-lambda * Math.max(0, ageHours));
        return Math.min(1.0, Math.max(0.0, recencyWeight));
    }

    /**
     * 对已评分的 Cell 施加时间衰减。
     */
    @Override
    public List<ScoredMemoryCell> rank(List<ScoredMemoryCell> cells, int topK) {
        return cells.stream()
                .map(sc -> {
                    double ageHours = Duration.between(sc.cell().timestamp(), Instant.now()).toHours();
                    double lambda = Math.log(2) / halfLifeHours;
                    double recencyWeight = baseWeight + (1.0 - baseWeight) * Math.exp(-lambda * Math.max(0, ageHours));
                    double newScore = sc.score() * recencyWeight;
                    return new ScoredMemoryCell(sc.cell(), newScore, sc.sourceType(), sc.explanation());
                })
                .sorted(java.util.Comparator.comparingDouble(ScoredMemoryCell::score).reversed())
                .limit(topK > 0 ? topK : 10)
                .toList();
    }
}
