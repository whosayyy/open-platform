package com.yin.ai.platform.memory.consolidation;

import com.yin.ai.platform.core.memory.MemoryCell;
import com.yin.ai.platform.core.memory.MemoryId;
import com.yin.ai.platform.core.memory.MemoryType;

import java.util.List;

/**
 * Memory Consolidator SPI — 从 Episodic Timeline 提取结构化知识，实现 STM → LTM 转移。
 * <p>
 * Consolidation 是 Memory 生命周期中的关键环节：
 * <ol>
 *   <li>Episodic Cells 随时间积累 → 超过阈值触发 Consolidation</li>
 *   <li>Consolidator 从 Timeline 中提取 Episode、Fact、Procedure</li>
 *   <li>提取结果写入 Semantic / Procedural Store</li>
 *   <li>已处理的 Cells 标记为 consolidated，避免重复提取</li>
 * </ol>
 * <p>
 * 触发条件由 {@link com.yin.ai.platform.memory.policy.ConsolidationPolicy} 控制，
 * Consolidator 本身是无状态的策略实现。
 * <p>
 * 实现：
 * <ul>
 *   <li>{@link EpisodeExtractionConsolidator} — 按时间/语义边界切分 Episode</li>
 *   <li>{@link FactExtractionConsolidator} — 关键词规则提取 Fact</li>
 *   <li>LLM-based Consolidator（Phase 6+）— LLM 驱动的深度提取</li>
 * </ul>
 */
public interface MemoryConsolidator {

    /**
     * 从 Episodic Cells 中 Consolidate 结构化知识。
     *
     * @param memoryId 所属 Memory
     * @param cells    按时间排序的 Episodic Cells
     * @return Consolidation 结果
     */
    ConsolidationResult consolidate(MemoryId memoryId, List<MemoryCell> cells);

    /** 支持的源/目标 Memory 类型转换 */
    default boolean supports(MemoryType from, MemoryType to) {
        return from == MemoryType.EPISODIC
                && (to == MemoryType.SEMANTIC || to == MemoryType.PROCEDURAL);
    }

    /** Consolidator 名称（调试用） */
    default String name() {
        return getClass().getSimpleName();
    }
}
