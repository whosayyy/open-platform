package com.yin.ai.platform.agent.rag;

import com.yin.ai.platform.rag.enhanced.RetrievedDocument;
import com.yin.ai.platform.rag.enhanced.RagRetrievalSnapshot;

import java.util.List;

/**
 * Agent ↔ RAG 桥接接口 — 供 ReActLoop 在 Plan 阶段注入 RAG 上下文。
 *
 * <p>【改动原因】将 EnhancedRagService（企业级 RAG 管道）全面接入 AgentRuntime，
 * 使 Agent 在规划前能检索知识库增强上下文，对齐 07-RAG.md §8（与 Agent 集成）。
 *
 * <p>设计原则（与 AgentMemoryBridge 一致）：
 * <ul>
 *   <li>纯 Java 接口，不依赖 Spring — ReActLoop 保持非 Spring 依赖</li>
 *   <li>实现类由 AgentAutoConfiguration 注入，降级时返回空字符串</li>
 *   <li>检索失败不阻塞 Agent 执行（降级返回空上下文）</li>
 * </ul>
 */
public interface AgentRagBridge {

    /**
     * 检索 RAG 上下文（供 Agent Plan/Act 阶段增强）。
     *
     * @param query  用户查询/目标
     * @param userId 用户 ID（用于 Gateway 审计，可为 null）
     * @return RAG 上下文字符串；无结果或不可用时返回空字符串
     */
    String retrieve(String query, Long userId);

    /**
     * 检索结构化 RAG 文档列表（供前端渲染引用卡片）。
     *
     * <p>与 {@link #retrieve} 配合使用：先调用 retrieve 获取上下文字符串注入 LLM，
     * 再调用此方法获取结构化文档列表，用于前端 "引用资料" 面板展示。
     *
     * @param query  用户查询/目标
     * @param userId 用户 ID（可为 null）
     * @return 检索到的文档列表；无结果或不可用时返回空列表
     */
    default List<RetrievedDocument> retrieveDocuments(String query, Long userId) {
        return List.of();
    }

    /**
     * 一次性取得上下文与结构化引用。新版调用方应优先使用本方法，避免重复检索。
     * 兼容实现会退化为旧的两个方法；默认实现会覆盖为真正的单次快照。
     */
    default RagRetrievalSnapshot retrieveSnapshot(String query, Long userId) {
        String context = retrieve(query, userId);
        List<RetrievedDocument> documents = retrieveDocuments(query, userId);
        return new RagRetrievalSnapshot("", query, query, documents, context,
                !documents.isEmpty(), documents.isEmpty() ? "no_retrieval_results" : "", 0);
    }

    /** RAG 桥接是否可用（EnhancedRagService 存在且 enabled） */
    boolean isAvailable();
}
