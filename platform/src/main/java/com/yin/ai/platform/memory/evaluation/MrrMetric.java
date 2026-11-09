package com.yin.ai.platform.memory.evaluation;

import java.util.List;

/**
 * Mean Reciprocal Rank — 首个相关文档排名的倒数。
 * MRR = 1/rank_of_first_relevant_doc
 */
public class MrrMetric implements EvaluationMetric {

    @Override
    public String name() {
        return "MRR";
    }

    @Override
    public double compute(List<RetrievedDocument> retrieved, List<String> relevantDocIds, int k) {
        if (retrieved.isEmpty() || relevantDocIds.isEmpty()) return 0.0;

        for (RetrievedDocument doc : retrieved) {
            if (doc.rank() > k) break;
            if (relevantDocIds.contains(doc.docId())) {
                return 1.0 / doc.rank();
            }
        }
        return 0.0;
    }
}
