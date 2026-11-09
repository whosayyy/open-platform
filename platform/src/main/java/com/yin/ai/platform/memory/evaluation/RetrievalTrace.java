package com.yin.ai.platform.memory.evaluation;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 单次检索溯源 — 记录完整的检索过程和结果。
 */
public record RetrievalTrace(
        String traceId,
        String query,
        String knowledgeBase,
        Instant timestamp,
        long durationMs,
        String retrieverType,
        int topK,
        double minScore,
        List<RetrievedDocument> retrievedDocs,
        List<String> expectedDocIds,
        Map<String, Double> metricScores) {

    public RetrievalTrace {
        retrievedDocs = retrievedDocs != null ? List.copyOf(retrievedDocs) : List.of();
        expectedDocIds = expectedDocIds != null ? List.copyOf(expectedDocIds) : List.of();
        metricScores = metricScores != null ? Collections.unmodifiableMap(metricScores) : Map.of();
    }
}
