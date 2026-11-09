package com.yin.ai.platform.core.memory;

/**
 * MemoryCell 之间的关系类型 — 构建 Knowledge Graph 的边类型。
 * CAUSAL:      A 导致 B（因果链）
 * TEMPORAL:    A 在 B 之前（时间序）
 * SEMANTIC:    A 与 B 语义相关（相似度）
 * REFERENCES:  A 引用 B（链接关系）
 * DERIVED_FROM: A 派生自 B（摘要、压缩、提取）
 */
public enum RelationType {
    CAUSAL,
    TEMPORAL,
    SEMANTIC,
    REFERENCES,
    DERIVED_FROM,
    DUPLICATE,
    REINFORCEMENT,
    SUPERSEDES,
    CONFLICTS_WITH,
    RELATED_TO
}
