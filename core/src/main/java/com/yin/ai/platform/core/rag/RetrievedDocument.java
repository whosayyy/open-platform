package com.yin.ai.platform.core.rag;

import java.util.Map;

/**
 * 检索结果文档 — 企业级 RAG 管道各阶段的统一数据载体。
 *
 * <p>【迁移自 wkc-ai-service】基座能力归属 platform，service 侧通过 adapter 或直接注入使用。
 * 实现 07-RAG.md V2.1/V2.2 规划的企业级 RAG 能力
 * （多路召回 + 问题重写 + BM25 混合 + RRF 排序 + 精排）。
 *
 * @param docId     文档唯一标识
 * @param title     标题
 * @param content   正文片段
 * @param score     相关性分数（不同阶段语义不同：召回分/RRF分/精排分）
 * @param source    召回来源（vector / bm25 / rrf / rerank）
 * @param metadata  元数据（来源、分类、发布时间等）
 */
public record RetrievedDocument(
        String docId,
        String title,
        String content,
        double score,
        String source,
        Map<String, String> metadata) {

    /** 创建带新分数和来源的副本（用于 RRF 融合/精排后更新） */
    public RetrievedDocument withScore(double newScore, String newSource) {
        return new RetrievedDocument(docId, title, content, newScore, newSource, metadata);
    }

    /** 截断内容用于 LLM 精排 Prompt（避免超 Token） */
    public RetrievedDocument truncateContent(int maxLen) {
        if (content == null || content.length() <= maxLen) return this;
        return new RetrievedDocument(docId, title, content.substring(0, maxLen), score, source, metadata);
    }
}
