package com.yin.ai.platform.memory.consolidation;

import com.yin.ai.platform.memory.episodic.Episode;
import com.yin.ai.platform.core.memory.procedural.Procedure;
import com.yin.ai.platform.core.memory.semantic.Fact;

import java.util.List;
import java.util.Map;

/**
 * Consolidation 结果 — 从 Episodic Timeline 中提取的结构化知识。
 * <p>
 * 一次 Consolidation 可以产出多种类型的结果：
 * <ul>
 *   <li>Episodes — 按语义边界切分的记忆片段</li>
 *   <li>Facts — 从对话中提取的事实三元组</li>
 *   <li>Procedures — 从任务执行中学习的过程</li>
 * </ul>
 *
 * @param episodes           提取的 Episode 列表
 * @param facts              提取的 Fact 列表
 * @param procedures         学习的 Procedure 列表
 * @param consolidatedCellIds 已处理的 Cell ID 集合（用于标记避免重复提取）
 * @param metadata           扩展元数据
 */
public record ConsolidationResult(
        List<Episode> episodes,
        List<Fact> facts,
        List<Procedure> procedures,
        List<String> consolidatedCellIds,
        Map<String, Object> metadata) {

    public ConsolidationResult {
        episodes = episodes != null ? List.copyOf(episodes) : List.of();
        facts = facts != null ? List.copyOf(facts) : List.of();
        procedures = procedures != null ? List.copyOf(procedures) : List.of();
        consolidatedCellIds = consolidatedCellIds != null
                ? List.copyOf(consolidatedCellIds) : List.of();
        metadata = metadata != null ? Map.copyOf(metadata) : Map.of();
    }

    public static ConsolidationResult empty() {
        return new ConsolidationResult(List.of(), List.of(), List.of(), List.of(), Map.of());
    }

    public boolean isEmpty() {
        return episodes.isEmpty() && facts.isEmpty() && procedures.isEmpty();
    }

    public int totalExtracted() {
        return episodes.size() + facts.size() + procedures.size();
    }
}
