package com.yin.ai.platform.memory.retrieval;

import com.yin.ai.platform.core.memory.api.MemoryQuery;
import com.yin.ai.platform.core.memory.api.ScoredMemoryCell;
import com.yin.ai.platform.core.memory.MemoryCell;

import java.util.List;

/**
 * Memory 检索器 SPI — 将 MemoryQuery 应用于 Cell 列表，返回带评分的排序结果。
 * <p>
 * 设计理由：检索策略独立于存储层。同一批 Cell 可以用不同 Retriever 检索，
 * 也可以组合多个 Retriever（如 HybridRetriever 融合 keyword + vector 结果）。
 * <p>
 * 实现：
 * <ul>
 *   <li>{@link KeywordRetriever} — 文本包含匹配 + TF 加权评分</li>
 *   <li>{@link HybridRetriever} — RRF 融合 keyword + vector 双路召回</li>
 *   <li>VectorRetriever（Phase 5+）— 纯向量相似度检索</li>
 * </ul>
 */
public interface MemoryRetriever {

    /**
     * 在 Cell 列表中检索匹配项。
     *
     * @param query 检索查询
     * @param cells 候选 Cell 列表
     * @return 按评分降序排列的结果列表
     */
    List<ScoredMemoryCell> retrieve(MemoryQuery query, List<MemoryCell> cells);

    /** 检索策略标识 */
    RetrievalStrategy strategy();
}
