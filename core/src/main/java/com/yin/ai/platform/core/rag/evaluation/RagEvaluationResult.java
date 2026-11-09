package com.yin.ai.platform.core.rag.evaluation;

import java.util.List;
import java.util.Map;
import java.time.Instant;

/**
 * RAG 测评结果 — 包含每个查询的指标分数和聚合分数。
 *
 * <p>【迁移自 wkc-ai-service】实现 07-RAG.md §13 RAG 可观测性中的检索质量评测结果模型。
 */
public record RagEvaluationResult(
        String datasetName,
        String datasetVersion,
        String evaluator,
        String evaluatorVersion,
        Instant evaluatedAt,
        int sampleCount,
        Double confidence,
        int topK,
        int totalQueries,
        List<QueryResult> results,
        Map<String, Double> aggregateScores) {

    public RagEvaluationResult(String datasetName, int totalQueries,
                               List<QueryResult> results, Map<String, Double> aggregateScores) {
        this(datasetName, "unversioned", "deterministic-ir", "1.0.0", Instant.now(),
                totalQueries, null, 5, totalQueries, results, aggregateScores);
    }

    public record QueryResult(
            String queryId,
            String query,
            int retrievedCount,
            int relevantRetrievedCount,
            Map<String, Double> scores) {}
}
