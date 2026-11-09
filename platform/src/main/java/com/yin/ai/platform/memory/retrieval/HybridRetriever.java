package com.yin.ai.platform.memory.retrieval;

import com.yin.ai.platform.core.memory.api.MemoryQuery;
import com.yin.ai.platform.core.memory.api.ScoredMemoryCell;
import com.yin.ai.platform.core.memory.MemoryCell;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 混合检索器 — RRF (Reciprocal Rank Fusion) 融合 keyword 和 vector 双路召回结果。
 * <p>
 * RRF 公式：score(d) = Σ 1/(k + rank_i(d))，其中 k 默认 60。
 * <p>
 * 融合策略：
 * <ol>
 *   <li>keyword 路：委托 {@link KeywordRetriever} 召回 topK*2 个候选</li>
 *   <li>vector 路：委托 vectorRetriever 召回 topK*2 个候选（若提供）</li>
 *   <li>RRF 融合两路排名 → 取 topK</li>
 * </ol>
 * <p>
 * 注意：vector 路依赖 {@link com.yin.ai.platform.core.memory.semantic.EmbeddingService}，
 * 若未注入则仅使用 keyword 路。
 */
public class HybridRetriever implements MemoryRetriever {

    private static final double RRF_K = 60.0;

    private final KeywordRetriever keywordRetriever;
    private final MemoryRetriever vectorRetriever;

    /**
     * @param vectorRetriever 向量检索器（可为 null，此时仅 keyword 召回）
     */
    public HybridRetriever(MemoryRetriever vectorRetriever) {
        this.keywordRetriever = new KeywordRetriever();
        this.vectorRetriever = vectorRetriever;
    }

    public HybridRetriever() {
        this(null);
    }

    @Override
    public List<ScoredMemoryCell> retrieve(MemoryQuery query, List<MemoryCell> cells) {
        int expandTopK = query.topK() * 2;

        // keyword 路召回
        List<ScoredMemoryCell> keywordResults = keywordRetriever.retrieve(
                query.withTopK(expandTopK), cells);

        // vector 路召回
        List<ScoredMemoryCell> vectorResults = List.of();
        if (vectorRetriever != null && query.vectorQuery() != null) {
            vectorResults = vectorRetriever.retrieve(query.withTopK(expandTopK), cells);
        }

        // 单路召回时直接返回
        if (keywordResults.isEmpty() && vectorResults.isEmpty()) {
            return List.of();
        }
        if (vectorResults.isEmpty()) {
            return keywordResults.stream().limit(query.topK()).toList();
        }
        if (keywordResults.isEmpty()) {
            return vectorResults.stream().limit(query.topK()).toList();
        }

        // RRF 融合
        return fuseRoutes(List.of(keywordResults, vectorResults), query.topK(), query.minScore());
    }

    @Override
    public RetrievalStrategy strategy() {
        return RetrievalStrategy.HYBRID;
    }

    // ==================== RRF ====================

    /**
     * 融合任意数量的检索路由（例如 ES BM25、关键词、向量）。RRF 原始值会按有效路由数
     * 归一化到 0-1，使 minScore 在混合检索后仍有明确语义。
     */
    public List<ScoredMemoryCell> fuseRoutes(List<List<ScoredMemoryCell>> routes,
                                             int topK, double minScore) {
        Map<String, Double> rrfScores = new HashMap<>();
        Map<String, ScoredMemoryCell> cellMap = new HashMap<>();
        int activeRoutes = 0;
        for (List<ScoredMemoryCell> route : routes) {
            if (route == null || route.isEmpty()) {
                continue;
            }
            activeRoutes++;
            addRRFScores(route, rrfScores, cellMap);
        }
        if (activeRoutes == 0) {
            return List.of();
        }

        List<ScoredMemoryCell> fused = new ArrayList<>();
        double theoreticalBest = activeRoutes / (RRF_K + 1.0);
        for (Map.Entry<String, Double> entry : rrfScores.entrySet()) {
            ScoredMemoryCell original = cellMap.get(entry.getKey());
            if (original != null) {
                double normalized = Math.min(1.0, entry.getValue() / theoreticalBest);
                if (normalized >= minScore) {
                    fused.add(new ScoredMemoryCell(original.cell(), normalized,
                            original.sourceType(), "HYBRID_RRF"));
                }
            }
        }

        return fused.stream()
                .sorted(Comparator.comparingDouble(ScoredMemoryCell::score).reversed())
                .limit(topK)
                .toList();
    }

    private void addRRFScores(List<ScoredMemoryCell> ranked,
                               Map<String, Double> rrfScores,
                               Map<String, ScoredMemoryCell> cellMap) {
        for (int i = 0; i < ranked.size(); i++) {
            ScoredMemoryCell cell = ranked.get(i);
            String key = cell.cell().cellId().id();
            double rrfScore = 1.0 / (RRF_K + i + 1);
            rrfScores.merge(key, rrfScore, Double::sum);
            // 更偏好带向量和完整属性的本地 Cell，避免 ES 轻量反序列化结果覆盖丰富版本。
            cellMap.merge(key, cell, (existing, candidate) ->
                    candidate.cell().vector().isPresent() && existing.cell().vector().isEmpty()
                            ? candidate : existing);
        }
    }

    /**
     * 检查是否有可用的向量检索器。
     */
    public boolean hasVectorRetriever() {
        return vectorRetriever != null;
    }
}
