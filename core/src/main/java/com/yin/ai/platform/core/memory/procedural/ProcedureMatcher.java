package com.yin.ai.platform.core.memory.procedural;

import java.util.List;

/**
 * Procedure 匹配器 SPI — 根据任务目标匹配可复用的 Procedure。
 * <p>
 * 匹配策略（按优先级）：
 * <ol>
 *   <li>Exact Match — goal 文本精确匹配</li>
 *   <li>Partial Match — 关键词/标签重叠度 ≥ 阈值</li>
 *   <li>Vector Match — goal 向量相似度（需向量嵌入，Phase 5+）</li>
 *   <li>Analogous Match — 结构相似度（步骤模式匹配，延后实现）</li>
 * </ol>
 */
public interface ProcedureMatcher {

    /**
     * 匹配 top-K 个最相关的 Procedure。
     *
     * @param goal  任务目标描述
     * @param candidates 候选 Procedure 列表（通常为 ACTIVE 状态）
     * @param topK  最大返回数量
     * @return 按匹配度降序排列的 Procedure 列表
     */
    List<Procedure> match(String goal, List<Procedure> candidates, int topK);

    /** 最低匹配阈值 [0.0, 1.0]，低于此分数不返回 */
    default double minScoreThreshold() {
        return 0.3;
    }
}
