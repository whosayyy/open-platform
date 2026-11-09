package com.yin.ai.platform.rag.enhanced;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yin.ai.platform.structured.StructuredClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * LLM 精排器 — 用 LLM 对 RRF 融合后的候选文档进行语义相关性精排。
 *
 * <p>【迁移自 wkc-ai-service】实现 07-RAG.md V2.2 规划的 Cross-Encoder ReRank（语义精排）能力。
 * 通过强制工具调用获取结构化评分（07-RAG.md §15 硬性规则，LLM 调用走 Gateway 统一通道）。
 * 降级策略：LLM 不可用或评分失败时，保持 RRF 排序不变。
 */
@Slf4j
@Component
public class LlmReranker {

    private static final int MAX_RERANK_CANDIDATES = 10;
    private static final int CONTENT_TRUNCATE_LEN = 200;

    private static final String RERANK_PROMPT_TEMPLATE = """
            你是一个文档相关性评分引擎。请对以下候选文档与查询的相关性评分（0-10分，10分最相关）。

            查询：%s

            候选文档：
            %s

            完成评分后必须调用 submitRerankScores 工具提交评分数组（工具参数即为结果 JSON，与候选文档顺序一致），不要输出任何文字。""";

    private static final ObjectMapper SCHEMA_MAPPER = new ObjectMapper();

    /** 精排工具契约：{scores:[number]} */
    private static final Map<String, Object> RERANK_SCHEMA = readSchema("""
            {"type":"object","properties":{"scores":{"type":"array",
              "description":"与候选文档顺序对应的评分数组(0-10分)","items":{"type":"number"}}},
             "required":["scores"]}
            """);

    private static final String RERANK_TOOL_DESCRIPTION =
            "提交文档相关性评分。完成评分后必须调用本工具，把评分数组作为工具参数返回，不要输出任何文字。";

    private static Map<String, Object> readSchema(String jsonSchema) {
        try {
            return SCHEMA_MAPPER.readValue(jsonSchema, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            throw new IllegalStateException("Invalid JSON schema constant", e);
        }
    }

    private final StructuredClient structuredClient;
    private final ObjectMapper objectMapper;

    public LlmReranker(StructuredClient structuredClient, ObjectMapper objectMapper) {
        this.structuredClient = structuredClient;
        this.objectMapper = objectMapper;
    }

    public List<RetrievedDocument> rerank(String query, List<RetrievedDocument> candidates,
                                          int topK, Long userId) {
        if (candidates == null || candidates.isEmpty()) return List.of();

        if (!structuredClient.isAvailable()) {
            log.debug("[RERANK] ChatClient 不可用，跳过精排");
            return candidates.stream().limit(topK).toList();
        }

        List<RetrievedDocument> toRerank = candidates.stream()
                .limit(Math.min(candidates.size(), MAX_RERANK_CANDIDATES))
                .map(d -> d.truncateContent(CONTENT_TRUNCATE_LEN))
                .toList();

        try {
            String docsBlock = buildDocsBlock(toRerank);
            String prompt = String.format(RERANK_PROMPT_TEMPLATE, query, docsBlock);

            String args = structuredClient.generate(
                    "submitRerankScores", RERANK_TOOL_DESCRIPTION, RERANK_SCHEMA, prompt, null, 0.0)
                    .orElse(null);
            if (args == null) {
                return candidates.stream().limit(topK).toList();
            }

            double[] scores = parseScores(args, toRerank.size());
            if (scores == null) {
                return candidates.stream().limit(topK).toList();
            }

            List<RetrievedDocument> reranked = new ArrayList<>();
            for (int i = 0; i < toRerank.size(); i++) {
                RetrievedDocument original = candidates.get(i);
                if (i < scores.length) {
                    reranked.add(original.withScore(scores[i], "rerank"));
                } else {
                    // 未评分的候选保留原分，避免静默丢失召回结果
                    reranked.add(original);
                }
            }

            reranked.sort(Comparator.comparingDouble(RetrievedDocument::score).reversed());
            log.info("[RERANK] 精排完成, candidates={}, reranked={}", toRerank.size(),
                    Math.min(reranked.size(), topK));
            return reranked.stream().limit(topK).toList();
        } catch (Exception e) {
            log.warn("[RERANK] 精排失败，保持 RRF 排序: {}", e.getMessage());
            return candidates.stream().limit(topK).toList();
        }
    }

    private String buildDocsBlock(List<RetrievedDocument> docs) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < docs.size(); i++) {
            RetrievedDocument d = docs.get(i);
            sb.append("[").append(i + 1).append("] ");
            if (d.title() != null && !d.title().isBlank()) sb.append(d.title()).append(": ");
            sb.append(d.content() != null ? d.content() : "").append("\n");
        }
        return sb.toString();
    }

    private double[] parseScores(String llmOutput, int expectedCount) {
        try {
            String json = llmOutput.trim();
            JsonNode root = objectMapper.readTree(json);
            JsonNode arr = root.isArray() ? root : root.get("scores");
            if (arr == null || !arr.isArray()) return null;

            double[] scores = new double[Math.min(arr.size(), expectedCount)];
            for (int i = 0; i < scores.length; i++) {
                JsonNode item = arr.get(i);
                if (item.isNumber()) {
                    scores[i] = item.asDouble();
                } else if (item.isObject()) {
                    scores[i] = item.has("score") ? item.get("score").asDouble()
                            : item.has("relevance") ? item.get("relevance").asDouble()
                            : item.has("rating") ? item.get("rating").asDouble()
                            : 0.0;
                } else {
                    scores[i] = 0.0;
                }
            }
            if (arr.size() != expectedCount) {
                log.warn("[RERANK] 评分数量不匹配, expected={}, actual={}", expectedCount, arr.size());
            }
            return scores;
        } catch (Exception e) {
            log.warn("[RERANK] 解析 LLM 评分失败: {}", e.getMessage());
            return null;
        }
    }

    public boolean isAvailable() {
        return structuredClient.isAvailable();
    }
}
