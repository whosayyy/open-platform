package com.yin.ai.platform.memory.evaluation;

import java.util.List;

/**
 * Precision@K — 前 K 个结果中相关文档的比例。
 * Precision@K = |retrieved ∩ relevant| / K
 */
public class PrecisionAtKMetric implements EvaluationMetric {

    @Override
    public String name() {
        return "Precision";
    }

    @Override
    public double compute(List<RetrievedDocument> retrieved, List<String> relevantDocIds, int k) {
        if (retrieved.isEmpty() || relevantDocIds.isEmpty()) return 0.0;

        long hitCount = retrieved.stream()
                .filter(d -> d.rank() <= k && relevantDocIds.contains(d.docId()))
                .limit(k)
                .count();

        return (double) hitCount / k;
    }
}
