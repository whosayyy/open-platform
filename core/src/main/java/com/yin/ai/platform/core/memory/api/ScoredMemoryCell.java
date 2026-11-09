package com.yin.ai.platform.core.memory.api;

import com.yin.ai.platform.core.memory.MemoryCell;
import com.yin.ai.platform.core.memory.MemoryType;

/**
 * 带评分的 MemoryCell — 检索排序的基本单位。
 * score 由 RelevanceRanker 计算（BM25/Cosine/RRF），sourceType 标识来自哪个 Memory 类型。
 */
public record ScoredMemoryCell(MemoryCell cell, double score, MemoryType sourceType, String explanation) {

    public static ScoredMemoryCell of(MemoryCell cell, double score, MemoryType sourceType) {
        return new ScoredMemoryCell(cell, score, sourceType, null);
    }
}
