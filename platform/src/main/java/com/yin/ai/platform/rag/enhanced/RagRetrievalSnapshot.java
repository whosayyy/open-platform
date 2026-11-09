package com.yin.ai.platform.rag.enhanced;

import java.util.List;

/**
 * 一次 RAG 检索的不可变快照。
 *
 * <p>上下文注入、前端引用、在线指标必须复用同一个快照，避免重复检索导致
 * “模型看到的证据”和“用户看到的引用”不一致。</p>
 */
public record RagRetrievalSnapshot(
        String traceId,
        String originalQuery,
        String searchQuery,
        List<RetrievedDocument> documents,
        String context,
        boolean evidenceSufficient,
        String insufficiencyReason,
        long durationMs) {

    public RagRetrievalSnapshot {
        documents = documents != null ? List.copyOf(documents) : List.of();
        context = context != null ? context : "";
        insufficiencyReason = insufficiencyReason != null ? insufficiencyReason : "";
    }

    public static RagRetrievalSnapshot empty(String traceId, String query, String reason,
                                             long durationMs) {
        return new RagRetrievalSnapshot(traceId, query, query, List.of(), "", false,
                reason, durationMs);
    }
}
