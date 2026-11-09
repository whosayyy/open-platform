package com.yin.ai.platform.memory.evaluation;

import java.util.List;
import java.util.Map;

/**
 * 评测数据集 — 查询 + 期望的相关文档 ID 列表。
 */
public record EvaluationDataset(
        String name,
        String knowledgeBase,
        List<QueryCase> queries) {

    public record QueryCase(
            String id,
            String query,
            List<String> relevantDocIds,
            Map<String, Integer> relevanceGrades) {

        public QueryCase {
            relevanceGrades = relevanceGrades != null ? Map.copyOf(relevanceGrades) : Map.of();
        }
    }
}
