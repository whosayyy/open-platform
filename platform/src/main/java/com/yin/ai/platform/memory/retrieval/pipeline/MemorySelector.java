package com.yin.ai.platform.memory.retrieval.pipeline;

import com.yin.ai.platform.core.memory.api.MemoryQuery;
import com.yin.ai.platform.core.memory.api.ScoredMemoryCell;
import com.yin.ai.platform.core.memory.MemoryCell;

import java.util.List;

/**
 * Memory 选择器 SPI — 从已排序的候选集中精选最终注入 Prompt 的 Cell。
 * <p>
 * 与 {@link com.yin.ai.platform.memory.retrieval.RelevanceRanker} 的分工：
 * <ul>
 *   <li>Ranker 负责评分（相关性、时间衰减、标签匹配）</li>
 *   <li>Selector 负责精选策略（多样性、去重、每个 MemoryType 的配额控制）</li>
 * </ul>
 * <p>
 * 典型策略：
 * <ul>
 *   <li>Top-K: 直接取前 K 条</li>
 *   <li>MMR (Maximal Marginal Relevance): 平衡相关性和多样性</li>
 *   <li>Quota: 每个 MemoryType 最多取 N 条</li>
 * </ul>
 */
public interface MemorySelector {

    /**
     * 从已排序的候选集中精选。
     *
     * @param rankedCells 已排序的候选集
     * @param query       原始查询
     * @return 精选后的 Cell 列表
     */
    List<MemoryCell> select(List<ScoredMemoryCell> rankedCells, MemoryQuery query);

    /** 选择器名称 */
    String name();
}
