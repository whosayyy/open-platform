package com.yin.ai.platform.agent.memory.consolidation;

import com.yin.ai.platform.core.memory.MemoryCell;

import java.util.List;

/**
 * 任务后记忆整合结果。
 *
 * @param semanticCells   提炼的事实/知识
 * @param proceduralCells 提炼的可复用流程
 * @param episodicCompressed 被压缩的 Episodic 条目数
 * @param summary         整合摘要
 */
public record ConsolidationResult(
        List<MemoryCell> semanticCells,
        List<MemoryCell> proceduralCells,
        int episodicCompressed,
        String summary
) {
    public static ConsolidationResult empty() {
        return new ConsolidationResult(List.of(), List.of(), 0, "no consolidation needed");
    }
}
