package com.yin.ai.platform.observe;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yin.ai.platform.core.memory.api.ScoredMemoryCell;
import com.yin.ai.platform.structured.StructuredClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 记忆召回判官（LLM-as-judge）— 评估召回的历史记忆与当前用户查询的相关性。
 *
 * <p>在线 Memory 质量指标的数据来源：召回记忆是否与问题相关 → 正确率；
 * 召回但判为不相关 → 误判率（false positive）。采样调用（默认 5%），
 * 失败返回空列表（指标列留空，不回退伪造）。
 */
@Slf4j
@Component
public class MemoryRetrievalJudge {

    /** 判官版本：改提示词/schema 时递增 */
    public static final String JUDGE_VERSION = "memory-judge-v1";

    private static final int MAX_CELLS = 5;
    private static final int CONTENT_TRUNCATE_LEN = 300;

    private static final String JUDGE_PROMPT_TEMPLATE = """
            你是记忆相关性评估员。请判断每条历史记忆与当前用户查询是否相关
            （记忆是否有助于回答当前问题），并调用 submitMemoryJudgments 工具提交结果。

            用户查询：%s

            召回的历史记忆（编号与记忆ID对应）：
            %s

            规则：只关心记忆内容与查询主题的相关性，不要求记忆能独立回答查询；
            明显无关（主题不符/过期/噪音）判 relevant=false。

            必须调用 submitMemoryJudgments 工具提交
            {"judgments":[{"cellId":"记忆ID","relevant":true或false}, ...]}，不要输出任何文字。""";

    private static final ObjectMapper SCHEMA_MAPPER = new ObjectMapper();

    private static final Map<String, Object> JUDGE_SCHEMA = readSchema("""
            {"type":"object","properties":{"judgments":{"type":"array","items":{"type":"object",
              "properties":{"cellId":{"type":"string"},"relevant":{"type":"boolean"}},
              "required":["cellId","relevant"]}}},"required":["judgments"]}
            """);

    private static final String JUDGE_TOOL_DESCRIPTION =
            "提交记忆相关性判定结果（judgments 数组，与召回记忆一一对应）。必须调用本工具，不要输出任何文字。";

    private final StructuredClient structuredClient;
    private final ObjectMapper objectMapper;

    public MemoryRetrievalJudge(StructuredClient structuredClient, ObjectMapper objectMapper) {
        this.structuredClient = structuredClient;
        this.objectMapper = objectMapper;
    }

    /** 判定结果：cellId → 是否相关。失败返回空列表（调用方按"未判定"处理）。 */
    public List<Judgment> judge(String query, List<ScoredMemoryCell> cells) {
        if (query == null || cells == null || cells.isEmpty()) {
            return List.of();
        }
        if (!structuredClient.isAvailable()) {
            log.debug("[MEM-JUDGE] ChatClient 不可用，跳过评估");
            return List.of();
        }
        try {
            List<ScoredMemoryCell> toJudge = cells.stream().limit(MAX_CELLS).toList();
            String block = buildCellsBlock(toJudge);
            String prompt = String.format(JUDGE_PROMPT_TEMPLATE, query, block);
            String args = structuredClient.generate(
                    "submitMemoryJudgments", JUDGE_TOOL_DESCRIPTION, JUDGE_SCHEMA, prompt, null, 0.0)
                    .orElse(null);
            if (args == null) {
                return List.of();
            }
            JsonNode root = objectMapper.readTree(args);
            JsonNode judgments = root.path("judgments");
            if (!judgments.isArray()) {
                return List.of();
            }
            List<Judgment> result = new ArrayList<>();
            for (JsonNode node : judgments) {
                String cellId = node.path("cellId").asText("");
                if (cellId.isBlank()) {
                    continue;
                }
                result.add(new Judgment(cellId, node.path("relevant").asBoolean(false)));
            }
            return result;
        } catch (Exception e) {
            log.warn("[MEM-JUDGE] 评估失败（指标留空）: {}", e.getMessage());
            return List.of();
        }
    }

    private String buildCellsBlock(List<ScoredMemoryCell> cells) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cells.size(); i++) {
            ScoredMemoryCell cell = cells.get(i);
            sb.append("[").append(i + 1).append("] cellId=")
                    .append(cell.cell().cellId().id())
                    .append(" 内容：")
                    .append(truncate(cell.cell().content(), CONTENT_TRUNCATE_LEN))
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

    /** 单条记忆判定：是否与查询相关。 */
    public record Judgment(String cellId, boolean relevant) {
    }
}
