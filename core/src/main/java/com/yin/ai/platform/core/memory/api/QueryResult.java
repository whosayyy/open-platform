package com.yin.ai.platform.core.memory.api;

import com.yin.ai.platform.core.memory.MemoryCell;
import com.yin.ai.platform.core.memory.MemoryType;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 检索结果 — 包含排序后的 ScoredMemoryCell 列表、总数和耗时。
 * resultCountByType 按 MemoryType 分组统计，用于调试检索分布。
 */
public record QueryResult(
        List<ScoredMemoryCell> results,
        long totalFound,
        Duration queryTime,
        Map<MemoryType, Integer> resultCountByType) {

    public QueryResult {
        results = results != null ? List.copyOf(results) : List.of();
        resultCountByType = resultCountByType != null ? Map.copyOf(resultCountByType) : Map.of();
    }

    public static QueryResult of(List<ScoredMemoryCell> results, long totalFound, Duration queryTime) {
        Map<MemoryType, Integer> counts = new HashMap<>();
        for (ScoredMemoryCell cell : results) {
            counts.merge(cell.sourceType(), 1, Integer::sum);
        }
        return new QueryResult(results, totalFound, queryTime, counts);
    }

    public boolean isEmpty() {
        return results.isEmpty();
    }
}
