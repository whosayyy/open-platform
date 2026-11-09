package com.yin.ai.platform.rag.enhanced;

import java.util.List;

/**
 * RAG 检索溯源记录器 — 每次检索完成后记录 trace + 文档明细。
 *
 * <p>platform 层定义接口，service 层提供 JDBC 实现（写入 ai_retrieval_trace / ai_retrieved_document）。
 * 无实现时降级为 no-op，不阻塞检索主流程。
 */
public interface RetrievalTraceRecorder {

    /**
     * 记录一次完整的 RAG 检索溯源。
     *
     * @param traceId       唯一溯源 ID
     * @param query         原始查询文本
     * @param knowledgeBase 知识库名称
     * @param retrieverType 检索器类型（HYBRID/VECTOR/KEYWORD）
     * @param topK          请求的 topK
     * @param minScore      最低分数阈值
     * @param durationMs    检索耗时（毫秒）
     * @param documents     最终返回的文档列表
     */
    void record(String traceId, String query, String knowledgeBase, String retrieverType,
                int topK, double minScore, long durationMs, List<RetrievedDocument> documents);

    /** No-op 降级实现 */
    RetrievalTraceRecorder NOOP = (traceId, query, kb, type, topK, minScore, durationMs, docs) -> {};
}
