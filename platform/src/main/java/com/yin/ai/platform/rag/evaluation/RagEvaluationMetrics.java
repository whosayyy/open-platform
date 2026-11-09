package com.yin.ai.platform.rag.evaluation;

import com.yin.ai.platform.rag.enhanced.RetrievedDocument;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * RAG 检索质量评测指标 — MRR / NDCG / Recall@K / Precision@K。
 *
 * <p>【迁移自 wkc-ai-service】实现 07-RAG.md §13 RAG 可观测性中的检索质量评测指标。
 */
public final class RagEvaluationMetrics {

    private RagEvaluationMetrics() {}

    public static double mrr(List<RetrievedDocument> retrieved, Set<String> relevantDocIds) {
        for (int i = 0; i < retrieved.size(); i++) {
            if (relevantDocIds.contains(retrieved.get(i).docId())) {
                return 1.0 / (i + 1);
            }
        }
        return 0.0;
    }

    public static double ndcg(List<RetrievedDocument> retrieved, Set<String> relevantDocIds, int k) {
        double dcg = 0.0;
        for (int i = 0; i < Math.min(retrieved.size(), k); i++) {
            if (relevantDocIds.contains(retrieved.get(i).docId())) {
                dcg += 1.0 / log2(i + 2);
            }
        }
        int relevantCount = Math.min(relevantDocIds.size(), k);
        double idcg = 0.0;
        for (int i = 0; i < relevantCount; i++) {
            idcg += 1.0 / log2(i + 2);
        }
        return idcg > 0 ? dcg / idcg : 0.0;
    }

    public static double recallAtK(List<RetrievedDocument> retrieved, Set<String> relevantDocIds, int k) {
        if (relevantDocIds.isEmpty()) return 0.0;
        Set<String> retrievedIds = new HashSet<>();
        for (int i = 0; i < Math.min(retrieved.size(), k); i++) {
            retrievedIds.add(retrieved.get(i).docId());
        }
        long relevantRetrieved = retrievedIds.stream().filter(relevantDocIds::contains).count();
        return (double) relevantRetrieved / relevantDocIds.size();
    }

    public static double precisionAtK(List<RetrievedDocument> retrieved, Set<String> relevantDocIds, int k) {
        int evaluated = Math.min(retrieved.size(), k);
        if (evaluated == 0) return 0.0;
        long relevant = 0;
        for (int i = 0; i < evaluated; i++) {
            if (relevantDocIds.contains(retrieved.get(i).docId())) relevant++;
        }
        return (double) relevant / evaluated;
    }

    /** Binary hit rate for one labelled query. */
    public static double hitRate(List<RetrievedDocument> retrieved, Set<String> relevantDocIds, int k) {
        for (int i = 0; i < Math.min(retrieved.size(), k); i++) {
            if (relevantDocIds.contains(retrieved.get(i).docId())) return 1.0;
        }
        return 0.0;
    }

    private static double log2(double x) { return Math.log(x) / Math.log(2); }
}
