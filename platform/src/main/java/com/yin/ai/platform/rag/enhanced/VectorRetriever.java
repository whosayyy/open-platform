package com.yin.ai.platform.rag.enhanced;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import com.yin.ai.platform.core.rag.RetrievedDocument;

/**
 * 向量检索器 — 基于 LangChain4j EmbeddingStore 的 kNN 语义检索。
 *
 * <p>【迁移自 wkc-ai-service】实现 07-RAG.md V2.0/V2.1 的向量召回路。
 * EmbeddingModel/EmbeddingStore 由 platform LangChain4jRAGConfig 条件装配
 * （@ConditionalOnProperty("ai.rag.enabled")），本类用 ObjectProvider 降级注入。
 *
 * <p>【分数说明】直接调用 {@link EmbeddingStore#search} 而非 {@code EmbeddingStoreContentRetriever}：
 * 后者返回 {@code List<Content>} 会丢弃 {@link EmbeddingMatch#score()}（真实相关性分数），
 * 曾导致用伪造的单调分数 1.0 - i*0.01 填充。本实现保留真实分数，供 RRF/融合/阈值过滤使用。
 */
@Slf4j
@Component
public class VectorRetriever {

    private final ObjectProvider<EmbeddingModel> embeddingModelProvider;
    private final ObjectProvider<EmbeddingStore<TextSegment>> embeddingStoreProvider;

    public VectorRetriever(ObjectProvider<EmbeddingModel> embeddingModelProvider,
                           ObjectProvider<EmbeddingStore<TextSegment>> embeddingStoreProvider) {
        this.embeddingModelProvider = embeddingModelProvider;
        this.embeddingStoreProvider = embeddingStoreProvider;
    }

    public List<RetrievedDocument> retrieve(String query, int topK, double minScore) {
        return retrieve(query, topK, minScore, null);
    }

    /**
     * 带多租户隔离的向量检索 — 只返回用户自己的知识库 + 公共知识库的文档。
     *
     * <p>namespace 格式：{userId}:{spaceName}（私有）或 public:{spaceName}（公共）。
     * 策略：过量召回（topK*3）→ 按 namespace 后过滤 → 截取 topK。
     *
     * @param userId 用户 ID（null 时不过滤，兼容旧调用）
     */
    public List<RetrievedDocument> retrieve(String query, int topK, double minScore, Long userId) {
        if (query == null || query.isBlank()) return List.of();

        EmbeddingModel model = embeddingModelProvider.getIfAvailable();
        EmbeddingStore<TextSegment> store = embeddingStoreProvider.getIfAvailable();
        if (model == null || store == null) {
            log.debug("[VECTOR] EmbeddingModel/EmbeddingStore 不可用（ai.rag.enabled 未开启？）");
            return List.of();
        }

        try {
            // 多租户时过量召回，为后过滤留余量
            int fetchK = userId != null ? topK * 3 : topK;

            // 直接调 store.search 拿真实分数（EmbeddingStoreContentRetriever 会丢弃 score）
            Embedding queryEmbedding = model.embed(query).content();
            EmbeddingSearchRequest searchRequest = EmbeddingSearchRequest.builder()
                    .queryEmbedding(queryEmbedding)
                    .maxResults(fetchK)
                    .minScore(minScore)
                    .build();
            EmbeddingSearchResult<TextSegment> searchResult = store.search(searchRequest);

            List<RetrievedDocument> results = new ArrayList<>();
            for (EmbeddingMatch<TextSegment> match : searchResult.matches()) {
                TextSegment segment = match.embedded();
                if (segment == null) continue;
                String docId = segment.metadata().getString("docId");
                String title = segment.metadata().getString("title");
                String text = segment.text();

                Map<String, String> metadata = new HashMap<>();
                segment.metadata().toMap().forEach((k, v) -> metadata.put(k, v != null ? v.toString() : ""));

                // 多租户 namespace 过滤
                if (userId != null) {
                    String namespace = metadata.get("namespace");
                    if (!isNamespaceAccessible(namespace, userId)) {
                        continue;
                    }
                }

                double score = match.score() != null ? match.score() : 0.0;
                results.add(new RetrievedDocument(
                        docId != null ? docId : "vec-" + results.size(),
                        title != null ? title : "",
                        text,
                        score,
                        "vector",
                        metadata));

                if (results.size() >= topK) break;
            }
            return results;
        } catch (Exception e) {
            log.warn("[VECTOR] 向量检索失败, query='{}', err={}", query, e.getMessage());
            return List.of();
        }
    }

    /**
     * 判断 namespace 是否对当前用户可见。
     * 规则：{userId}:* 私有可见 | public:* 公共可见 | 其他用户的不可见。
     * namespace 为空时视为公共（兼容未标注 namespace 的历史数据）。
     */
    private static boolean isNamespaceAccessible(String namespace, Long userId) {
        if (namespace == null || namespace.isBlank()) {
            return true; // 未标注 namespace 的历史数据视为公共
        }
        if (namespace.startsWith("public:")) {
            return true;
        }
        return namespace.startsWith(userId + ":");
    }

    public boolean isAvailable() {
        return embeddingModelProvider.getIfAvailable() != null
                && embeddingStoreProvider.getIfAvailable() != null;
    }
}
