package com.yin.ai.platform.core.rag.evaluation;

import java.util.List;

/**
 * RAG 测评数据集 — 一组标注了相关文档的查询用例。
 *
 * <p>【迁移自 wkc-ai-service】实现 07-RAG.md §13 RAG 可观测性中的检索质量评测数据模型。
 */
public record RagEvaluationDataset(
        String name,
        String version,
        List<QueryCase> queries) {

    public RagEvaluationDataset(String name, List<QueryCase> queries) {
        this(name, "unversioned", queries);
    }

    public RagEvaluationDataset {
        version = version == null || version.isBlank() ? "unversioned" : version;
    }

    public record QueryCase(
            String id,
            String query,
            List<String> relevantDocIds) {}
}
