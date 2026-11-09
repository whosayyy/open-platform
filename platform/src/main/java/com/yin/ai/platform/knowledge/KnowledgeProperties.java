package com.yin.ai.platform.knowledge;

import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 知识库全局配置（application.yml 中 {@code ai.knowledge.*} 段）。
 */
@Data
public class KnowledgeProperties {

    /** 主开关：为 false 时全局不触发知识检索 */
    private boolean enabled = false;

    /** 各知识库配置，key = 知识库名称 */
    private Map<String, KnowledgeBaseConfig> bases = new LinkedHashMap<>();

    /** 最大上下文 token 数（用于结果截断） */
    private int maxContextTokens = 3000;

    @Data
    public static class KnowledgeBaseConfig {
        /** 检索类型：vector（向量）/ keyword（关键词）/ hybrid（混合）/ graph（图谱） */
        private String type = "vector";

        /** ES 索引名（vector 类型必填） */
        private String indexName;

        /** 最大返回文档数 */
        private int maxDocuments = 5;

        /** 最低相似度阈值（0.0~1.0） */
        private double rerankThreshold = 0.7;

        /** 候选乘数：粗排取 maxDocuments * candidateMultiplier 个候选再精排 */
        private int candidateMultiplier = 3;
    }
}
