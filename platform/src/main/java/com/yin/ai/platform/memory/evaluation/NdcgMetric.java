package com.yin.ai.platform.memory.evaluation;

import java.util.List;

/**
 * Normalized Discounted Cumulative Gain — 考虑排名位置的累积增益。
 * DCG@k = Σ(rel_i / log2(i+1)), IDCG = 理想排序的 DCG, NDCG = DCG/IDCG
 */
public class NdcgMetric implements EvaluationMetric {

    @Override
    public String name() {
        return "NDCG";
    }

    @Override
    public double compute(List<RetrievedDocument> retrieved, List<String> relevantDocIds, int k) {
        if (retrieved.isEmpty() || relevantDocIds.isEmpty()) return 0.0;

        double dcg = 0.0;
        for (RetrievedDocument doc : retrieved) {
            if (doc.rank() > k) break;
            int rel = relevantDocIds.contains(doc.docId()) ? 1 : 0;
            dcg += rel / (Math.log(doc.rank() + 1) / Math.log(2));
        }

        // ideal DCG: all relevant docs ranked at top
        double idcg = 0.0;
        int idealCount = Math.min(relevantDocIds.size(), k);
        for (int i = 0; i < idealCount; i++) {
            idcg += 1.0 / (Math.log(i + 2) / Math.log(2));
        }

        return idcg > 0 ? dcg / idcg : 0.0;
    }
}
