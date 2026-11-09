package com.yin.ai.platform.memory.evaluation;

import java.util.Collections;
import java.util.Map;

/**
 * 单个被检索文档的溯源信息 — 完整的"从哪来 → 什么分 → 排第几"链路。
 */
public record RetrievedDocument(
        int rank,
        double score,
        String retrieverSource,
        String docId,
        String title,
        String knowledgeBase,
        String chunkId,
        int chunkIndex,
        String headingPath,
        long startOffset,
        long endOffset,
        String contentPreview,
        Map<String, Object> metadata) {

    public RetrievedDocument {
        metadata = metadata != null ? Collections.unmodifiableMap(metadata) : Map.of();
    }
}
