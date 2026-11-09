package com.yin.ai.platform.memory.retrieval;

import com.yin.ai.platform.core.memory.api.MemoryQuery;
import com.yin.ai.platform.core.memory.api.ScoredMemoryCell;
import com.yin.ai.platform.core.memory.MemoryCell;

import java.util.Comparator;
import java.util.List;

/** 使用 MemoryCell 自带向量执行余弦相似度检索。 */
public class VectorMemoryRetriever implements MemoryRetriever {

    @Override
    public List<ScoredMemoryCell> retrieve(MemoryQuery query, List<MemoryCell> cells) {
        if (query == null || query.vectorQuery() == null || cells == null || cells.isEmpty()) {
            return List.of();
        }
        return cells.stream()
                .filter(cell -> cell.vector().isPresent())
                .filter(cell -> query.searchTypes().isEmpty()
                        || query.searchTypes().contains(cell.memoryType()))
                .map(cell -> {
                    double cosine;
                    try {
                        cosine = query.vectorQuery().cosineSimilarity(cell.vector().orElseThrow());
                    } catch (IllegalArgumentException mismatch) {
                        cosine = 0.0;
                    }
                    double score = Math.max(0.0, Math.min(1.0, cosine));
                    return new ScoredMemoryCell(cell, score, cell.memoryType(), "VECTOR_COSINE");
                })
                .filter(result -> result.score() > 0 && result.score() >= query.minScore())
                .sorted(Comparator.comparingDouble(ScoredMemoryCell::score).reversed())
                .limit(query.topK())
                .toList();
    }

    @Override
    public RetrievalStrategy strategy() {
        return RetrievalStrategy.VECTOR;
    }
}
