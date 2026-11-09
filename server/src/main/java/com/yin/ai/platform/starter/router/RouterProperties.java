package com.yin.ai.platform.starter.router;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Intent Router 配置属性。
 *
 * <pre>
 * ai:
 *   router:
 *     default-intent: chat
 *     short-circuit-enabled: true
 *     keyword-mappings:
 *       "摘要": "summary"
 *       "审核": "review"
 *     regex-patterns:
 *       review:
 *         pre-filter-keywords: ["审核", "review"]
 *         patterns: ["(审核|review).*(内容|article)"]
 *     metadata-rules:
 *       - metadata-key: "contentId"
 *         condition-key: "action"
 *         condition-value: "review"
 *         intent: "review"
 *     embedding-intent-descriptions:
 *       summary: "总结概括摘要"
 *       review: "审核审阅检查"
 * </pre>
 */
@Data
@Component
@ConfigurationProperties(prefix = "ai.router")
public class RouterProperties {

    /** 无匹配时的默认意图。 */
    private String defaultIntent = "chat";

    /** 是否启用短路匹配（第一个匹配成功即返回）。 */
    private boolean shortCircuitEnabled = true;

    /** 关键词 → 意图 映射表（KeywordMatcher 使用）。 */
    private Map<String, String> keywordMappings = Map.of();

    /** Application-owned deterministic phrase normalization mappings. */
    private Map<String, String> normalizerMappings = Map.of();

    /** Intent → Regex 模式组 映射表（RegexMatcher 使用）。 */
    private Map<String, RegexPatternGroup> regexPatterns = Map.of();

    /** 元数据规则列表（RuleMatcher 使用）。 */
    private List<MetadataRule> metadataRules = List.of();

    /** Intent → 语义描述 映射表（EmbeddingMatcher 使用）。 */
    private Map<String, String> embeddingIntentDescriptions = Map.of();

    @Data
    public static class RegexPatternGroup {
        private List<String> preFilterKeywords = List.of();
        private List<String> patterns = List.of();
    }

    @Data
    public static class MetadataRule {
        private String metadataKey;
        private String conditionKey;
        private String conditionValue;
        private String intent;
    }
}
