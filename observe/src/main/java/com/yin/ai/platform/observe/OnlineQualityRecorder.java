package com.yin.ai.platform.observe;

import com.yin.ai.platform.core.memory.api.QueryResult;
import com.yin.ai.platform.core.rag.RetrievedDocument;

import java.util.List;

/**
 * 在线检索质量采集器 SPI — 对每个真实用户查询的 RAG / Memory 检索链路采集质量指标。
 *
 * <p>平台模块声明接口（如 {@code RetrievalTraceRecorder}），service 模块提供
 * JDBC 实现；平台侧通过 ObjectProvider 可选注入，未接线时静默跳过。
 * 实现必须失败不抛异常（采集链路绝不阻断检索/回答）。
 */
public interface OnlineQualityRecorder {

    /** 无操作回退实现。 */
    OnlineQualityRecorder NOOP = new OnlineQualityRecorder() {
        @Override
        public void recordRagRetrieval(String traceId, String query, Long userId,
                                       String knowledgeBase, String retrieverType,
                                       int topK, List<RetrievedDocument> docs) {
        }

        @Override
        public void recordMemoryRecall(Long userId, String query, QueryResult result) {
        }

        @Override
        public void recordAgentAnswer(String taskId, Long userId, String goal, String answer) {
        }
    };

    /** RAG 每次在线检索（复用精排分计算正确率/召回 proxy，零 LLM 成本）。 */
    void recordRagRetrieval(String traceId, String query, Long userId, String knowledgeBase,
                            String retrieverType, int topK, List<RetrievedDocument> docs);

    /** Agent 每次记忆召回（召回数/均分/来源路径）。 */
    void recordMemoryRecall(Long userId, String query, QueryResult result);

    /** Agent 答案完成（采样 LLM judge：RAG 忠诚度 + 记忆相关性/误判率回填）。 */
    void recordAgentAnswer(String taskId, Long userId, String goal, String answer);
}
