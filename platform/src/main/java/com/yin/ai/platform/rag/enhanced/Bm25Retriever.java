package com.yin.ai.platform.rag.enhanced;

import com.yin.ai.platform.rag.ElasticsearchDocService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * BM25 检索器 — 基于 IK 中文分词的 Elasticsearch 关键词检索。
 *
 * <p>【迁移自 wkc-ai-service】实现 07-RAG.md V2.1 "BM25+向量混合检索"中的 BM25 路。
 * 委托 {@link ElasticsearchDocService} 执行实际检索，复用已配置 SSL/Auth 的
 * {@link co.elastic.clients.elasticsearch.ElasticsearchClient}，无需自建 HTTP 连接。
 *
 * <p>索引创建时已配置 ik_max_word 建库 + ik_smart 检索 + 中文停用词，
 * 对中文新闻内容具有优秀的召回率和精确度。
 */
@Slf4j
@Component
public class Bm25Retriever {

    private final ElasticsearchDocService docService;

    public Bm25Retriever(ElasticsearchDocService docService) {
        this.docService = docService;
    }

    public List<RetrievedDocument> retrieve(String query, int topK) {
        return retrieve(query, topK, null);
    }

    /**
     * 带多租户隔离的 BM25 检索 — 只返回用户自己的 + 公共知识库的文档。
     *
     * @param userId 用户 ID（null 时不过滤，兼容旧调用）
     */
    public List<RetrievedDocument> retrieve(String query, int topK, Long userId) {
        if (query == null || query.isBlank()) return List.of();

        try {
            return docService.searchByKeyword(query, topK, userId);
        } catch (Exception e) {
            log.warn("[BM25] 检索失败, query='{}', err={}", query, e.getMessage());
            return List.of();
        }
    }
}
