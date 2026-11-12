package com.yin.ai.platform.agent.rag;

import com.yin.ai.platform.rag.enhanced.EnhancedRagService;
import com.yin.ai.platform.core.rag.RetrievedDocument;
import com.yin.ai.platform.rag.enhanced.RagRetrievalSnapshot;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * AgentRagBridge 默认实现 — 委托 {@link EnhancedRagService} 执行企业级 RAG 检索。
 *
 * <p>【改动原因】将企业级 RAG 管道（问题重写→多路召回→RRF→精排）接入 AgentRuntime，
 * 使 Agent 在 Plan 阶段能基于知识库检索增强规划。
 *
 * <p>降级策略：EnhancedRagService 不可用时返回空字符串，不阻塞 Agent 执行。
 *
 * <p>注意：这里不使用 @ConditionalOnMissingBean，避免自引用条件导致 Bean 无法注册。
 * AgentAutoConfiguration 中的 no-op fallback 使用 @ConditionalOnMissingBean 确保
 * 只有在本 Component 未被扫描到时才创建降级 Bean。
 */
@Slf4j
@Component
public class DefaultAgentRagBridge implements AgentRagBridge {

    private final ObjectProvider<EnhancedRagService> ragServiceProvider;

    public DefaultAgentRagBridge(ObjectProvider<EnhancedRagService> ragServiceProvider) {
        this.ragServiceProvider = ragServiceProvider;
    }

    @Override
    public String retrieve(String query, Long userId) {
        EnhancedRagService service = ragServiceProvider.getIfAvailable();
        if (service == null || !service.isAvailable()) {
            log.debug("[RAG-BRIDGE] EnhancedRagService 不可用，跳过 RAG 上下文注入");
            return "";
        }
        try {
            String context = service.retrieve(query, userId);
            log.info("[RAG-BRIDGE] RAG 上下文注入成功, queryLen={}, contextLen={}",
                    query != null ? query.length() : 0, context.length());
            return context;
        } catch (Exception e) {
            log.warn("[RAG-BRIDGE] RAG 检索失败，降级返回空上下文: {}", e.getMessage());
            return "";
        }
    }

    @Override
    public List<RetrievedDocument> retrieveDocuments(String query, Long userId) {
        EnhancedRagService service = ragServiceProvider.getIfAvailable();
        if (service == null || !service.isAvailable()) {
            return List.of();
        }
        try {
            List<RetrievedDocument> docs = service.retrieveDocuments(query, userId);
            log.info("[RAG-BRIDGE] 结构化文档检索成功, queryLen={}, docCount={}",
                    query != null ? query.length() : 0, docs.size());
            return docs;
        } catch (Exception e) {
            log.warn("[RAG-BRIDGE] 结构化文档检索失败: {}", e.getMessage());
            return List.of();
        }
    }

    @Override
    public RagRetrievalSnapshot retrieveSnapshot(String query, Long userId) {
        EnhancedRagService service = ragServiceProvider.getIfAvailable();
        if (service == null || !service.isAvailable()) {
            return RagRetrievalSnapshot.empty("", query, "rag_unavailable", 0);
        }
        try {
            RagRetrievalSnapshot snapshot = service.retrieveSnapshot(query, userId);
            log.info("[RAG-BRIDGE] 单次检索快照完成, traceId={}, contextLen={}, docCount={}, sufficient={}",
                    snapshot.traceId(), snapshot.context().length(), snapshot.documents().size(),
                    snapshot.evidenceSufficient());
            return snapshot;
        } catch (Exception e) {
            log.warn("[RAG-BRIDGE] RAG 快照检索失败: {}", e.getMessage());
            return RagRetrievalSnapshot.empty("", query, "retrieval_failed", 0);
        }
    }

    @Override
    public boolean isAvailable() {
        EnhancedRagService service = ragServiceProvider.getIfAvailable();
        return service != null && service.isAvailable();
    }
}
