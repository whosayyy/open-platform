package com.yin.ai.platform.agent.memory.consolidation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yin.ai.platform.agent.memory.strategy.ModeMemoryStrategy.ConsolidationPolicy;
import com.yin.ai.platform.agent.pattern.AgentExecutionContext;
import com.yin.ai.platform.core.memory.api.MemoryRuntime;
import com.yin.ai.platform.core.memory.MemoryCell;
import com.yin.ai.platform.core.memory.MemoryCellId;
import com.yin.ai.platform.core.memory.MemoryContext;
import com.yin.ai.platform.core.memory.MemoryId;
import com.yin.ai.platform.core.memory.MemoryType;
import com.yin.ai.platform.structured.StructuredClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 默认记忆整合引擎 — 用 LLM 从执行时间线提炼长期知识。
 * <p>
 * 异步执行（@Async），不阻塞任务完成响应。
 * 使用低成本模型（不需要最强模型做提炼）。
 */
public class DefaultMemoryConsolidationEngine implements MemoryConsolidationEngine {

    private static final Logger log = LoggerFactory.getLogger(DefaultMemoryConsolidationEngine.class);

    /** 语义事实提炼契约：{facts:[string]} */
    private static final Map<String, Object> SEMANTIC_FACTS_SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "facts", Map.of(
                            "type", "array",
                            "description", "提炼出的事实陈述列表",
                            "items", Map.of("type", "string"))),
            "required", List.of("facts"));

    /** 流程模板提炼契约：{goalCategory, steps:[string], tips:[string]} */
    private static final Map<String, Object> PROCEDURE_SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "goalCategory", Map.of("type", "string", "description", "流程所属目标类别"),
                    "steps", Map.of("type", "array", "description", "可复用的步骤列表",
                            "items", Map.of("type", "string")),
                    "tips", Map.of("type", "array", "description", "经验提示列表",
                            "items", Map.of("type", "string"))),
            "required", List.of("goalCategory", "steps"));

    private static final String SEMANTIC_FACTS_TOOL_NAME = "submitSemanticFacts";
    private static final String SEMANTIC_FACTS_TOOL_DESCRIPTION =
            "提交提炼出的语义事实。完成提炼后必须调用本工具，把事实数组作为工具参数返回，不要输出任何文字。";

    private static final String PROCEDURE_TOOL_NAME = "submitProcedure";
    private static final String PROCEDURE_TOOL_DESCRIPTION =
            "提交提炼出的流程模板。完成提炼后必须调用本工具，把流程对象作为工具参数返回，不要输出任何文字。";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final MemoryRuntime memoryRuntime;
    private final StructuredClient structuredClient;

    public DefaultMemoryConsolidationEngine(MemoryRuntime memoryRuntime,
                                            StructuredClient structuredClient) {
        this.memoryRuntime = memoryRuntime;
        this.structuredClient = structuredClient;
    }

    @Override
    public ConsolidationResult consolidate(AgentExecutionContext execCtx,
                                            ConsolidationPolicy policy,
                                            List<MemoryCell> timeline) {
        // 门控检查
        if (timeline == null || timeline.size() < policy.minStepsToTrigger()) {
            return ConsolidationResult.empty();
        }

        String taskId = execCtx.getTaskId();
        log.info("[CONSOLIDATION] taskId={} timeline={} steps, extracting...", taskId, timeline.size());

        List<MemoryCell> semanticCells = new ArrayList<>();
        List<MemoryCell> proceduralCells = new ArrayList<>();

        try {
            // Semantic 提炼
            if (policy.extractSemantic()) {
                List<String> facts = extractSemanticFacts(execCtx, timeline);
                for (String fact : facts) {
                    MemoryCell cell = MemoryCell.of(
                                    MemoryCellId.of("sem-" + taskId + "-" + semanticCells.size()),
                                    MemoryType.SEMANTIC, fact)
                            .withTag("source", "consolidation")
                            .withTag("mode", execCtx.getAgentMode().name())
                            .withAttribute("ownerKey", execCtx.getUserId());
                    semanticCells.add(cell);
                }
            }

            // Procedural 归档
            if (policy.extractProcedural() && isReusablePattern(execCtx, timeline)) {
                String procedure = extractProcedure(execCtx, timeline);
                if (procedure != null && !procedure.isBlank()) {
                    MemoryCell cell = MemoryCell.of(
                                    MemoryCellId.of("proc-" + taskId),
                                    MemoryType.PROCEDURAL, procedure)
                            .withTag("source", "consolidation")
                            .withTag("goal", execCtx.getGoal().substring(0, Math.min(50, execCtx.getGoal().length())))
                            .withAttribute("ownerKey", execCtx.getUserId());
                    proceduralCells.add(cell);
                }
            }

            // 批量写入
            if (!semanticCells.isEmpty() || !proceduralCells.isEmpty()) {
                List<MemoryCell> all = new ArrayList<>(semanticCells);
                all.addAll(proceduralCells);
                MemoryId targetId = MemoryId.of(
                        "consolidation-" + taskId, MemoryType.SEMANTIC, execCtx.getUserId());
                MemoryContext memCtx = MemoryContext.empty().withOperation("consolidation").withAttribute("userId", execCtx.getUserId());
                memoryRuntime.appendBatch(targetId, all, memCtx);
            }

            log.info("[CONSOLIDATION] taskId={} done: {} semantic, {} procedural",
                    taskId, semanticCells.size(), proceduralCells.size());

        } catch (Exception e) {
            log.error("[CONSOLIDATION] taskId={} failed: {}", taskId, e.getMessage());
        }

        return new ConsolidationResult(semanticCells, proceduralCells, 0,
                "extracted " + semanticCells.size() + " facts, " + proceduralCells.size() + " procedures");
    }

    private List<String> extractSemanticFacts(AgentExecutionContext ctx, List<MemoryCell> timeline) {
        String timelineText = timeline.stream()
                .map(MemoryCell::content)
                .limit(50)
                .reduce("", (a, b) -> a + "\n" + b);

        String prompt = """
                从以下任务执行时间线中提炼 3-5 条长期有价值的事实性知识。
                要求：独立可检索的事实陈述，避免时效性信息，优先提取用户偏好、领域知识、工具经验。
                完成提炼后必须调用 submitSemanticFacts 工具提交事实数组（工具参数即为结果 JSON），不要输出任何文字。

                任务目标：%s
                时间线：%s
                """.formatted(ctx.getGoal(), timelineText.substring(0, Math.min(3000, timelineText.length())));

        try {
            Optional<String> args = structuredClient.generate(
                    SEMANTIC_FACTS_TOOL_NAME, SEMANTIC_FACTS_TOOL_DESCRIPTION,
                    SEMANTIC_FACTS_SCHEMA, prompt, null, 0.2);
            return args.map(DefaultMemoryConsolidationEngine::parseFacts).orElseGet(List::of);
        } catch (Exception e) {
            log.warn("[CONSOLIDATION] semantic extraction failed: {}", e.getMessage());
            return List.of();
        }
    }

    private String extractProcedure(AgentExecutionContext ctx, List<MemoryCell> timeline) {
        String prompt = """
                从以下任务执行过程中提炼可复用的流程模板。
                完成提炼后必须调用 submitProcedure 工具提交流程模板（工具参数即为结果 JSON），不要输出任何文字。

                任务目标：%s
                执行模式：%s
                """.formatted(ctx.getGoal(), ctx.getAgentMode().name());

        try {
            Optional<String> args = structuredClient.generate(
                    PROCEDURE_TOOL_NAME, PROCEDURE_TOOL_DESCRIPTION,
                    PROCEDURE_SCHEMA, prompt, null, 0.2);
            return args.orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    private boolean isReusablePattern(AgentExecutionContext ctx, List<MemoryCell> timeline) {
        // 只有步骤数 >= 3 的任务才值得形成流程模板
        return timeline.size() >= 3;
    }

    private static List<String> parseFacts(String argsJson) {
        try {
            JsonNode node = MAPPER.readTree(argsJson);
            JsonNode facts = node.get("facts");
            if (facts == null || !facts.isArray()) return List.of();
            List<String> result = new ArrayList<>();
            for (JsonNode f : facts) {
                result.add(f.asText());
            }
            return result;
        } catch (Exception e) {
            log.warn("[CONSOLIDATION] parse semantic facts failed: {}", e.getMessage());
            return List.of();
        }
    }
}
