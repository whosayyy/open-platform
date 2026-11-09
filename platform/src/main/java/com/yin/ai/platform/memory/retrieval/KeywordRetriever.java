package com.yin.ai.platform.memory.retrieval;

import com.yin.ai.platform.core.memory.api.MemoryQuery;
import com.yin.ai.platform.core.memory.api.ScoredMemoryCell;
import com.yin.ai.platform.core.memory.MemoryCell;

import java.util.ArrayList;
import java.util.List;

/**
 * 关键词检索器 — 基于文本包含匹配 + TF 加权评分。
 * <p>
 * 评分逻辑：
 * <ol>
 *   <li>基础分 = 查询词命中次数 / 查询词总数（关键词覆盖度）</li>
 *   <li>额外加分：完全精确匹配 +0.1，标签匹配每个 +0.05</li>
 *   <li>低于 {@link MemoryQuery#minScore()} 的结果被过滤</li>
 * </ol>
 * <p>
 * 注意：此实现为简单文本匹配，不涉及 TF-IDF 或 BM25。
 * 生产环境可替换为 Elasticsearch / Lucene 实现。
 */
public class KeywordRetriever implements MemoryRetriever {

    @Override
    public List<ScoredMemoryCell> retrieve(MemoryQuery query, List<MemoryCell> cells) {
        if (query.textQuery() == null || query.textQuery().isBlank()) {
            return List.of();
        }

        String[] queryTerms = query.textQuery().toLowerCase().split("\\s+");
        List<ScoredMemoryCell> results = new ArrayList<>();

        for (MemoryCell cell : cells) {
            // 过滤标签
            if (!query.tags().isEmpty()) {
                boolean hasTag = query.tags().stream()
                        .anyMatch(t -> cell.tags().containsKey(t) || cell.tags().containsValue(t));
                if (!hasTag) {
                    continue;
                }
            }

            double score = computeScore(cell, queryTerms);
            if (score > 0 && score >= query.minScore()) {
                results.add(new ScoredMemoryCell(cell, score, cell.memoryType(), "KEYWORD_TF"));
            }
        }

        // 按评分降序 + topK 截断
        return results.stream()
                .sorted((a, b) -> Double.compare(b.score(), a.score()))
                .limit(query.topK())
                .toList();
    }

    @Override
    public RetrievalStrategy strategy() {
        return RetrievalStrategy.KEYWORD;
    }

    private double computeScore(MemoryCell cell, String[] queryTerms) {
        String lowerContent = cell.content().toLowerCase();
        int hits = 0;

        for (String term : queryTerms) {
            if (lowerContent.contains(term)) {
                hits++;
            }
        }

        double score = queryTerms.length > 0
                ? (double) hits / queryTerms.length
                : 0;

        // 精确匹配加分
        if (lowerContent.equals(String.join(" ", queryTerms))) {
            score = Math.min(score + 0.1, 1.0);
        }

        // 标签匹配加分
        if (!cell.tags().isEmpty()) {
            long tagMatches = queryTerms.length > 1
                    ? cell.tags().values().stream()
                        .filter(v -> lowerContent.contains(v.toLowerCase()))
                        .count()
                    : 0;
            score = Math.min(score + tagMatches * 0.05, 1.0);
        }

        return score;
    }
}
