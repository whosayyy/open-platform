package com.yin.ai.platform.memory.retrieval;

/**
 * 检索策略枚举 — 对应 Memory 检索的三种模式。
 */
public enum RetrievalStrategy {
    /** 关键词匹配 — 基于 BM25/contains 精确匹配 */
    KEYWORD,
    /** 向量相似度 — 基于 Embedding cosine similarity */
    VECTOR,
    /** 混合检索 — RRF (Reciprocal Rank Fusion) 融合 keyword 和 vector 结果 */
    HYBRID
}
