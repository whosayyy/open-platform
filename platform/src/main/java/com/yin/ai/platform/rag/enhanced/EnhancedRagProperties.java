package com.yin.ai.platform.rag.enhanced;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 企业级 RAG 增强配置 — 绑定 ai.rag.enhanced.* 命名空间。
 *
 * <p>【迁移自 wkc-ai-service】基座能力归属 platform。
 * 允许按需启用/禁用问题重写、BM25 混合、RRF、精排各阶段。
 */
@Data
@Component
@ConfigurationProperties(prefix = "ai.rag.enhanced")
public class EnhancedRagProperties {

    private boolean enabled = true;
    private int topK = 5;
    /** 复杂查询允许扩展的最大 Top-K。 */
    private int maxTopK = 10;
    /** 超过该字符数的查询按复杂查询扩展召回。 */
    private int complexQueryLength = 40;
    private int candidateMultiplier = 3;
    private double minScore = 0.7;
    /** LLM reranker uses a 0-10 scale; lower-scored documents are not cited or injected. */
    private double minRerankScore = 5.0;
    /** 允许进入生成阶段的最少独立证据数。 */
    private int minEvidenceCount = 1;
    private int maxContextTokens = 3000;
    /** 每个来源文档最多保留的片段数（防止 top-K 全部来自同一文档） */
    private int maxChunksPerSource = 2;

    private QueryRewriteConfig queryRewrite = new QueryRewriteConfig();
    private MultiQueryConfig multiQuery = new MultiQueryConfig();
    private Bm25Config bm25 = new Bm25Config();
    private RerankConfig rerank = new RerankConfig();
    private GroundingConfig grounding = new GroundingConfig();

    @Data
    public static class QueryRewriteConfig { private boolean enabled = true; }

    @Data
    public static class MultiQueryConfig {
        /** 同时检索原始查询与改写查询，随后统一 RRF。 */
        private boolean enabled = true;
        /** LLM expansion is opt-in; deterministic synonym rules remain preferred. */
        private boolean llmExpansionEnabled = false;
        /** Original query plus expansions. */
        private int maxQueries = 3;
    }

    @Data
    public static class Bm25Config { private boolean enabled = true; }

    @Data
    public static class RerankConfig { private boolean enabled = true; }

    @Data
    public static class GroundingConfig {
        private boolean enabled = true;
        private boolean requireCitations = true;
        /** Require factual paragraphs/list items to carry their own evidence marker. */
        private boolean requireClaimLevelCitations = true;
        /** Minimum supported factual-claim coverage before the independent judge runs. */
        private double minClaimCitationCoverage = 0.90;
        private boolean failClosed = true;
        private double minFaithfulness = 0.98;
        private int maxRegenerationAttempts = 1;
    }
}
