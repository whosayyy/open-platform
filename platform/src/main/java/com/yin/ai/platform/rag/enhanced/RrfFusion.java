package com.yin.ai.platform.rag.enhanced;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * RRF (Reciprocal Rank Fusion) 融合器 — 将多路召回结果按排名融合。
 *
 * <p>【迁移自 wkc-ai-service】实现 07-RAG.md V2.1 规划的"BM25+向量混合检索"中的 RRF 排序能力。
 * RRF 公式：score(d) = Σ 1/(k + rank_i(d))，k 默认 60。
 * 优势：无需归一化各路分数，仅按排名融合。
 */
public final class RrfFusion {

    private static final double DEFAULT_K = 60.0;
    private final double k;

    public RrfFusion() { this(DEFAULT_K); }
    public RrfFusion(double k) { this.k = k; }

    public List<RetrievedDocument> fuse(List<List<RetrievedDocument>> rankedRoutes, int topK) {
        if (rankedRoutes == null || rankedRoutes.isEmpty()) return List.of();

        Map<String, Double> rrfScores = new HashMap<>();
        Map<String, RetrievedDocument> docMap = new HashMap<>();

        for (List<RetrievedDocument> route : rankedRoutes) {
            if (route == null || route.isEmpty()) continue;
            List<RetrievedDocument> sorted = route.stream()
                    .sorted(Comparator.comparingDouble(RetrievedDocument::score).reversed())
                    .toList();
            for (int i = 0; i < sorted.size(); i++) {
                RetrievedDocument doc = sorted.get(i);
                String key = doc.docId() != null ? doc.docId() : doc.title();
                if (key == null) continue;
                double rrfScore = 1.0 / (k + i + 1);
                rrfScores.merge(key, rrfScore, Double::sum);
                docMap.putIfAbsent(key, doc);
            }
        }

        List<RetrievedDocument> fused = new ArrayList<>();
        for (Map.Entry<String, Double> entry : rrfScores.entrySet()) {
            RetrievedDocument original = docMap.get(entry.getKey());
            if (original != null) {
                fused.add(original.withScore(entry.getValue(), "rrf"));
            }
        }

        return fused.stream()
                .sorted(Comparator.comparingDouble(RetrievedDocument::score).reversed())
                .limit(topK)
                .toList();
    }
}
