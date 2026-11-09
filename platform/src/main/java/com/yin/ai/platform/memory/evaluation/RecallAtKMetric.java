package com.yin.ai.platform.memory.evaluation;

import java.util.List;

/**
 * Recall@K — 前 K 个结果中召回了多少相关文档。
 * Recall@K = |retrieved ∩ relevant| / |relevant|
 */
public class RecallAtKMetric implements EvaluationMetric {

    @Override
    public String name() {
        return "Recall";
    }

    @Override
    public double compute(List<RetrievedDocument> retrieved, List<String> relevantDocIds, int k) {
        if (retrieved.isEmpty() || relevantDocIds.isEmpty()) return 0.0;

        long hitCount = retrieved.stream()
                .filter(d -> d.rank() <= k && relevantDocIds.contains(d.docId()))
                .count();

        return (double) hitCount / relevantDocIds.size();
    }
}
