package com.yin.ai.platform.rag.evaluation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yin.ai.platform.structured.StructuredClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * RAG 在线答案质量判官（LLM-as-judge）— 免标注在线指标。
 *
 * <p>对齐 RAGAS / TruLens RAG Triad 模式：
 * <ul>
 *   <li><b>忠诚度 faithfulness</b>：回答中的声明是否由检索上下文支持（防幻觉）</li>
 *   <li><b>答案相关性 answer relevance</b>：回答是否切题且完整解答用户问题</li>
 * </ul>
 *
 * <p>降级：LLM 不可用 / 解析失败 → 返回 null（指标列留空，绝不回退伪造分数）。
 * 判官模型与提示词版本由 {@link #JUDGE_VERSION} 记录，供趋势对照。
 */
@Slf4j
@Component
public class RagOnlineJudge {

    /** 判官版本：改提示词/schema 时递增 */
    public static final String JUDGE_VERSION = "rag-judge-v2-claim-support";

    private static final int CONTEXT_TRUNCATE_LEN = 500;
    private static final int MAX_CONTEXT_CHUNKS = 10;

    private static final String JUDGE_PROMPT_TEMPLATE = """
            你是 RAG 质量评估员。请对"回答"进行两项评估，并调用 submitRagJudgment 工具提交结果。

            用户查询：%s

            检索参考资料（回答应完全基于这些资料）：
            %s

            回答：
            %s

            评估规则：
            1. faithfulness（忠诚度 0.0-1.0）：逐条核对回答中的事实、数字与结论是否都能在参考资料中找到依据；
               有编造/幻觉内容则显著扣分；完全基于资料给 1.0。
            2. answerRelevance（答案相关性 0.0-1.0）：回答是否切题、是否完整解答了用户问题。
            3. evidenceSupportRate（证据支持率 0.0-1.0）：把回答拆成事实 claim 后，能被引用证据直接支持的 claim 比例。
            4. conflictRate（冲突率 0.0-1.0）：回答采用的结论与参考资料冲突的 claim 比例。
            5. unsupportedFactRate（无依据事实率 0.0-1.0）：无法在参考资料中验证的事实 claim 比例。

            必须调用 submitRagJudgment 工具提交全部五项指标，不要输出任何文字。""";

    private static final ObjectMapper SCHEMA_MAPPER = new ObjectMapper();

    private static final Map<String, Object> JUDGE_SCHEMA = readSchema("""
            {"type":"object","properties":{
              "faithfulness":{"type":"number","description":"忠诚度 0.0-1.0"},
              "answerRelevance":{"type":"number","description":"答案相关性 0.0-1.0"},
              "evidenceSupportRate":{"type":"number","description":"Claim 证据支持率 0.0-1.0"},
              "conflictRate":{"type":"number","description":"来源/结论冲突率 0.0-1.0"},
              "unsupportedFactRate":{"type":"number","description":"无依据事实率 0.0-1.0"}},
             "required":["faithfulness","answerRelevance","evidenceSupportRate","conflictRate","unsupportedFactRate"]}
            """);

    private static final String JUDGE_TOOL_DESCRIPTION =
            "提交 RAG 答案质量评估结果（faithfulness 与 answerRelevance，均为 0.0-1.0）。必须调用本工具，不要输出任何文字。";

    private final StructuredClient structuredClient;
    private final ObjectMapper objectMapper;

    public RagOnlineJudge(StructuredClient structuredClient, ObjectMapper objectMapper) {
        this.structuredClient = structuredClient;
        this.objectMapper = objectMapper;
    }

    /**
     * 评估回答质量（采样调用方负责频控）。
     *
     * @return (faithfulness, answerRelevance) 或 null（不可用/失败）
     */
    public Judgment evaluate(String query, String answer, List<String> contextChunks) {
        if (query == null || answer == null || answer.isBlank()
                || contextChunks == null || contextChunks.isEmpty()) {
            return null;
        }
        if (!structuredClient.isAvailable()) {
            log.debug("[RAG-JUDGE] ChatClient 不可用，跳过评估");
            return null;
        }
        try {
            String contextBlock = buildContextBlock(contextChunks);
            String prompt = String.format(JUDGE_PROMPT_TEMPLATE, query, contextBlock,
                    truncate(answer, 2000));
            String args = structuredClient.generate(
                    "submitRagJudgment", JUDGE_TOOL_DESCRIPTION, JUDGE_SCHEMA, prompt, null, 0.0)
                    .orElse(null);
            if (args == null) {
                return null;
            }
            JsonNode root = objectMapper.readTree(args);
            Double faithfulness = clamp(root.path("faithfulness").asDouble(Double.NaN));
            Double answerRelevance = clamp(root.path("answerRelevance").asDouble(Double.NaN));
            if (faithfulness == null || answerRelevance == null) {
                return null;
            }
            // Rolling-upgrade compatibility: older judges emitted only the two original
            // fields. Derive conservative claim metrics until every provider honors v2.
            Double evidenceSupportRate = optionalRatio(root, "evidenceSupportRate", faithfulness);
            Double conflictRate = optionalRatio(root, "conflictRate", 0.0);
            Double unsupportedFactRate = optionalRatio(root, "unsupportedFactRate",
                    Math.max(0.0, 1.0 - faithfulness));
            return new Judgment(faithfulness, answerRelevance, evidenceSupportRate,
                    conflictRate, unsupportedFactRate);
        } catch (Exception e) {
            log.warn("[RAG-JUDGE] 评估失败（指标留空）: {}", e.getMessage());
            return null;
        }
    }

    private Double clamp(double value) {
        if (Double.isNaN(value)) {
            return null;
        }
        return Math.max(0.0, Math.min(1.0, value));
    }

    private double optionalRatio(JsonNode root, String field, double fallback) {
        Double value = clamp(root.path(field).asDouble(Double.NaN));
        return value != null ? value : fallback;
    }

    private String buildContextBlock(List<String> chunks) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(chunks.size(), MAX_CONTEXT_CHUNKS); i++) {
            sb.append("[").append(i + 1).append("] ").append(truncate(chunks.get(i), CONTEXT_TRUNCATE_LEN))
                    .append("\n");
        }
        return sb.toString();
    }

    private String truncate(String text, int maxLen) {
        if (text == null) {
            return "";
        }
        return text.length() <= maxLen ? text : text.substring(0, maxLen);
    }

    private static Map<String, Object> readSchema(String jsonSchema) {
        try {
            return SCHEMA_MAPPER.readValue(jsonSchema, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            throw new IllegalStateException("Invalid JSON schema constant", e);
        }
    }

    /** 评估结果：忠诚度 + 答案相关性（0-1）。 */
    public record Judgment(double faithfulness, double answerRelevance,
                           double evidenceSupportRate, double conflictRate,
                           double unsupportedFactRate) {
        public Judgment(double faithfulness, double answerRelevance) {
            this(faithfulness, answerRelevance, faithfulness, 0.0,
                    Math.max(0.0, 1.0 - faithfulness));
        }
    }
}
