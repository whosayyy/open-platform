package com.yin.ai.platform.memory.evaluation;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 评测结果 — 包含逐查询指标明细。
 */
public record EvaluationResult(
        String datasetName,
        String knowledgeBase,
        int totalQueries,
        Map<String, Double> aggregatedScores,
        List<QueryResult> queryResults) {

    public record QueryResult(
            String queryId,
            String query,
            Map<String, Double> scores,
            RetrievalTrace trace) {
    }

    public static EvaluationResult of(String datasetName, String knowledgeBase,
                                       List<QueryResult> results) {
        Map<String, Double> aggregated = new LinkedHashMap<>();
        if (!results.isEmpty()) {
            List<String> metricNames = results.get(0).scores().keySet().stream().toList();
            for (String metric : metricNames) {
                double avg = results.stream()
                        .mapToDouble(r -> r.scores().getOrDefault(metric, 0.0))
                        .average().orElse(0.0);
                aggregated.put(metric, Math.round(avg * 10000.0) / 10000.0);
            }
        }
        return new EvaluationResult(datasetName, knowledgeBase, results.size(), aggregated, results);
    }
}
