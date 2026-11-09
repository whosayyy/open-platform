package com.yin.ai.platform.agent.pattern.reflect;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yin.ai.platform.agent.pattern.AgentExecutionContext;
import com.yin.ai.platform.agent.pattern.PlanJsonParser;
import com.yin.ai.platform.agent.pattern.TaskStep;
import com.yin.ai.platform.structured.StructuredClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * LLM 反思器 — 用 LLM 评估步骤输出质量并决定下一步行动。
 * <p>
 * 评估维度：
 * 1. 输出是否为空/无意义
 * 2. 输出是否回答了步骤描述的问题
 * 3. 信息是否充分（对研究类步骤）
 * 4. 是否需要追加研究轮次
 */
public class LlmStepReflector implements StepReflector {

    private static final Logger log = LoggerFactory.getLogger(LlmStepReflector.class);
    private static final int MIN_MEANINGFUL_LENGTH = 50;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 反思决策工具契约：{decision:[enum CONTINUE/RETRY/REPLAN/SKIP_TO_SYNTHESIS], reason, hint, newSteps} */
    private static final Map<String, Object> REFLECTION_SCHEMA = readSchema("""
            {"type":"object","properties":{
              "decision":{"type":"string","description":"CONTINUE=继续 / RETRY=重试 / REPLAN=追加 / SKIP=跳过综合",
                "enum":["CONTINUE","RETRY","REPLAN","SKIP_TO_SYNTHESIS"]},
              "reason":{"type":"string","description":"决策理由"},
              "hint":{"type":"string","description":"重试改进方向（decision=RETRY 时）"},
              "newSteps":{"type":"array","description":"追加步骤（decision=REPLAN 时必填，每条含 id/description/executor/dependsOn）",
                "items":{"type":"object",
                  "properties":{
                    "id":{"type":"string","description":"步骤 id，需唯一，不要与既有步骤重复"},
                    "description":{"type":"string","description":"步骤描述，明确说明要做什么"},
                    "executor":{"type":"string","description":"执行器：llm 或 web_search，不要使用其他值",
                      "enum":["llm","web_search"]},
                    "dependsOn":{"type":"array","items":{"type":"string"},"description":"依赖的既有步骤 id（可为空数组）"}},
                  "required":["id","description","executor"]}}},
             "required":["decision"]}
            """);

    private static final String REFLECTION_TOOL_NAME = "submitReflection";

    private static final String REFLECTION_TOOL_DESCRIPTION =
            "提交步骤评估决策。完成评估后必须调用本工具，把 decision/reason/hint/newSteps 作为工具参数返回，不要输出任何文字。";

    private static Map<String, Object> readSchema(String jsonSchema) {
        try {
            return MAPPER.readValue(jsonSchema, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            throw new IllegalStateException("Invalid JSON schema constant", e);
        }
    }

    private final StructuredClient structuredClient;
    private final Predicate<String> availableExecutor;

    public LlmStepReflector(StructuredClient structuredClient) {
        this(structuredClient, null);
    }

    public LlmStepReflector(StructuredClient structuredClient, Predicate<String> availableExecutor) {
        this.structuredClient = structuredClient;
        this.availableExecutor = availableExecutor;
    }

    @Override
    public ReflectResult reflect(TaskStep step, String output, AgentExecutionContext ctx) {
        // 快速规则检查（零 LLM 开销）
        if (output == null || output.isBlank()) {
            return ReflectResult.retry("步骤输出为空");
        }
        if (output.length() < MIN_MEANINGFUL_LENGTH) {
            return ReflectResult.retry("步骤输出过短（" + output.length() + " 字符），可能未充分执行");
        }

        // LLM 深度评估
        try {
            String prompt = buildReflectPrompt(step, output, ctx);
            String args = structuredClient.generate(
                    REFLECTION_TOOL_NAME, REFLECTION_TOOL_DESCRIPTION, REFLECTION_SCHEMA,
                    prompt, null, 0.2).orElse(null);
            return parseReflectResponse(args);
        } catch (Exception e) {
            log.warn("[REFLECT] LLM evaluation failed, defaulting to CONTINUE: {}", e.getMessage());
            return ReflectResult.continueExecution();
        }
    }

    private String buildReflectPrompt(TaskStep step, String output, AgentExecutionContext ctx) {
        return """
                你是一个质量评估器。评估以下步骤的执行结果。

                任务目标：%s
                步骤描述：%s
                步骤输出（前 2000 字符）：
                %s

                请判断：
                1. 输出是否充分回答了步骤的问题？
                2. 信息质量是否足够？
                3. 是否需要补充研究？

                决策取值说明：
                - CONTINUE：质量合格，继续下一步
                - RETRY：质量不足，需要重试（hint 中说明改进方向）
                - REPLAN：需要追加额外步骤（newSteps 中给出具体步骤，每条含 id/description/executor，executor 只能取 llm 或 web_search）
                - SKIP_TO_SYNTHESIS：信息已充分，可以跳过剩余步骤直接综合

                选择 REPLAN 时必须给出 newSteps，追加步骤的 dependsOn 可引用既有步骤 id（如信息收集类步骤），
                以便追加步骤复用已有产出。完成评估后必须调用 submitReflection 工具提交决策（工具参数即为结果 JSON），不要输出任何文字。
                """.formatted(
                ctx.getGoal(),
                step.description(),
                output.substring(0, Math.min(2000, output.length()))
        );
    }

    private ReflectResult parseReflectResponse(String response) {
        if (response == null || response.isBlank()) return ReflectResult.continueExecution();

        try {
            JsonNode node = MAPPER.readTree(response);
            String decision = node.path("decision").asText("").toUpperCase().replace('-', '_');
            String reason = node.path("reason").asText("");
            String hint = node.path("hint").asText("");
            return switch (decision) {
                case "RETRY" -> ReflectResult.retry(!hint.isBlank() ? hint : "需要更详细的输出");
                case "REPLAN" -> ReflectResult.replan(!reason.isBlank() ? reason : "需要追加研究步骤",
                        PlanJsonParser.parseNewSteps(response, availableExecutor));
                case "SKIP_TO_SYNTHESIS" -> ReflectResult.skipToSynthesis(!reason.isBlank() ? reason : "信息已充分");
                default -> ReflectResult.continueExecution();
            };
        } catch (Exception e) {
            log.warn("[REFLECT] 解析反思结果失败，默认 CONTINUE: {}", e.getMessage());
            return ReflectResult.continueExecution();
        }
    }
}
