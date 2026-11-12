package com.yin.ai.platform.rag;

import lombok.Data;

/**
 * RAG 全局配置属性（application.yml 中 ai.rag.* 段）。
 * <p>
 * 提供环境级别的 ES 基础设施默认值，KnowledgeConfiguration 中 VectorKnowledgeBase 使用此配置作为索引名兜底。
 */
@Data
public class RAGProperties {

    /** 主开关：为 false 时所有 Skill 不触发 RAG，零开销 */
    private boolean enabled = false;

    /** Elasticsearch hosts（逗号分隔） */
    private String esHosts = "localhost:9200";

    /** Elasticsearch 用户名 */
    private String esUsername = "elastic";

    /** Elasticsearch 密码 */
    private String esPassword = "";

    /** 是否跳过 SSL 证书验证（仅 dev 环境） */
    private boolean esInsecure = false;

    /** Elasticsearch 索引名 */
    private String esIndex = "wkc-documents";

    /** Embedding 模型名 */
    private String embeddingModel = "text-embedding-3-small";

    /** 向量维度（BGE Small ZH = 384，OpenAI text-embedding-3-small = 1536） */
    private int dimension = 384;

    /** 粗排候选乘数：从 ES 取 maxDocuments * CANDIDATE_MULTIPLIER 个候选，再精排取 Top-N */
    private int candidateMultiplier = 3;

    /** 最大上下文 token 数（截断阈值，按 1 token ≈ 2.5 中文字符估算） */
    private int maxContextTokens = 3000;

    /** 检索超时毫秒 */
    private int retrieveTimeoutMs = 5000;
}
