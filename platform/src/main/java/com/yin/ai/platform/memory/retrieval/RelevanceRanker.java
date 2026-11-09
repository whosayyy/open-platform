package com.yin.ai.platform.memory.retrieval;

import com.yin.ai.platform.core.memory.api.MemoryQuery;
import com.yin.ai.platform.core.memory.api.ScoredMemoryCell;
import com.yin.ai.platform.core.memory.MemoryCell;

import java.util.Comparator;
import java.util.List;

/**
 * 相关性排序器 SPI — 对 MemoryCell 进行评分和排序。
 * <p>
 * 评分维度：
 * <ul>
 *   <li>文本匹配度（关键词命中率、TF-IDF）</li>
 *   <li>时间衰减（最近的消息权重更高）</li>
 *   <li>标签匹配（tags 交集大小）</li>
 *   <li>来源权重（不同 MemoryType 优先级不同）</li>
 * </ul>
 */
public interface RelevanceRanker {

    /**
     * 计算单个 Cell 与查询的相关性评分 [0.0, 1.0]。
     */
    double score(MemoryCell cell, MemoryQuery query);

    /**
     * 对已评分的 Cell 列表进行排序（降序）、去重、截断。
     *
     * @param cells 已评分的 Cell 列表
     * @param topK  保留数量
     * @return 排序截断后的列表
     */
    default List<ScoredMemoryCell> rank(List<ScoredMemoryCell> cells, int topK) {
        return cells.stream()
                .sorted(Comparator.comparingDouble(ScoredMemoryCell::score).reversed())
                .limit(topK > 0 ? topK : 10)
                .toList();
    }
}
