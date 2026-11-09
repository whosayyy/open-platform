package com.yin.ai.platform.core.rag.document;

import java.util.Collections;
import java.util.Map;

/**
 * 平台统一文档模型 — 与业务领域无关。
 * <p>
 * 用于 RAG 检索结果返回，不依赖 LangChain4j 的 Document。
 */
public record WkcDocument(
        String docId,
        String title,
        String content,
        Map<String, Object> metadata
) {
    public WkcDocument {
        metadata = metadata != null ? Collections.unmodifiableMap(metadata) : Map.of();
    }
}
