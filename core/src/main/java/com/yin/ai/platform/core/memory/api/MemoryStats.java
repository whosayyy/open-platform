package com.yin.ai.platform.core.memory.api;

import com.yin.ai.platform.core.memory.MemoryType;

import java.util.Map;

/**
 * Memory 聚合统计 — 用于 Micrometer 指标暴露和容量监控。
 */
public record MemoryStats(
        long totalMemories,
        long totalCells,
        Map<MemoryType, Long> cellsByType,
        long activeWorkingMemories,
        long totalSizeBytes,
        double averageCellsPerMemory) {

    public MemoryStats {
        cellsByType = cellsByType != null ? Map.copyOf(cellsByType) : Map.of();
    }
}
