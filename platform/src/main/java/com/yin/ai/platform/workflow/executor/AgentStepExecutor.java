package com.yin.ai.platform.workflow.executor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yin.ai.platform.structured.StructuredClient;
import com.yin.ai.platform.workflow.model.Artifact;
import com.yin.ai.platform.workflow.model.ArtifactType;
import com.yin.ai.platform.workflow.model.ExecutorType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Agent 步骤执行器 — 面向需要多工具调用的研究型步骤。
 *
 * <p>流程：LLM 规划查询（plannerSpec）→ 程序确定性执行工具（ResearchStrategy.runToolCalls）
 * → LLM 合成产物（synthesizerSpec）。模型只做规划与综合，不直接控制工具调用顺序。
 */
@Slf4j
@Component
public class AgentStepExecutor implements StepExecutor {

    private static final double PLAN_TEMPERATURE = 0.2;
    private static final double SYNTH_TEMPERATURE = 0.3;

    private final StructuredClient structuredClient;
    private final ObjectProvider<ResearchStrategy> strategyProvider;
    private final ObjectMapper objectMapper;

    public AgentStepExecutor(StructuredClient structuredClient,
                             ObjectProvider<ResearchStrategy> strategyProvider,
                             ObjectMapper objectMapper) {
        this.structuredClient = structuredClient;
        this.strategyProvider = strategyProvider;
        this.objectMapper = objectMapper;
    }

    @Override
    public ExecutorType type() {
        return ExecutorType.AGENT;
    }

    @Override
    public StepExecutionResult execute(StepExecutionContext context) {
        ResearchStrategy strategy = strategyProvider.getIfAvailable();
        if (strategy == null) {
            return StepExecutionResult.fail("NO_RESEARCH_STRATEGY", "no ResearchStrategy bean available");
        }
        ArtifactType outputType = context.step().outputType();
        if (outputType == null) {
            return StepExecutionResult.fail("NO_OUTPUT_SPEC", "AGENT step requires output artifact");
        }

        // 1. 规划查询
        StructuredOutputSpec planner = strategy.plannerSpec();
        if (planner == null) {
            return StepExecutionResult.fail("NO_PLANNER_SPEC", "research strategy has no planner spec");
        }
        Optional<String> planJson = structuredClient.generate(
                planner.toolName(), planner.description(), planner.schema(),
                context.userPrompt(), context.systemPrompt(), PLAN_TEMPERATURE);
        if (planJson.isEmpty()) {
            return StepExecutionResult.fail("PLAN_FAILED", "research planning produced no output");
        }
        List<ResearchQuery> queries = parseQueries(planJson.get());
        if (queries.isEmpty()) {
            return StepExecutionResult.fail("NO_QUERIES", "research plan contained no queries");
        }

        // 2. 确定性工具执行（freshnessType→days 由策略转换）
        List<ResearchToolResult> results = strategy.runToolCalls(queries, context);

        // 3. 合成证据产物
        StructuredOutputSpec synth = strategy.synthesizerSpec();
        if (synth == null) {
            return StepExecutionResult.fail("NO_SYNTH_SPEC", "research strategy has no synthesizer spec");
        }
        String synthPrompt = buildSynthPrompt(context, results);
        Optional<String> synthJson = structuredClient.generate(
                synth.toolName(), synth.description(), synth.schema(),
                synthPrompt, context.systemPrompt(), SYNTH_TEMPERATURE);
        if (synthJson.isEmpty()) {
            return StepExecutionResult.fail("SYNTH_FAILED", "evidence synthesis produced no output");
        }
        try {
            Object content = objectMapper.readValue(synthJson.get(), Object.class);
            Artifact artifact = Artifact.of(context.taskRun().getTaskRunId(),
                    context.step().getId(), outputType, content);
            return StepExecutionResult.ok(artifact);
        } catch (Exception e) {
            log.warn("[WF-AGENT] parse {} failed: {}", synth.toolName(), e.getMessage());
            return StepExecutionResult.fail("SYNTH_PARSE_ERROR", e.getMessage());
        }
    }

    private List<ResearchQuery> parseQueries(String json) {
        try {
            JsonNode node = objectMapper.readTree(json);
            JsonNode arr = node.has("queries") ? node.get("queries") : node;
            if (arr == null || !arr.isArray()) {
                return List.of();
            }
            List<ResearchQuery> list = new ArrayList<>();
            for (JsonNode q : arr) {
                String query = q.has("query") ? q.get("query").asText() : "";
                String freshness = q.has("freshnessType") ? q.get("freshnessType").asText() : null;
                if (!query.isBlank()) {
                    list.add(new ResearchQuery(query, freshness));
                }
            }
            return list;
        } catch (Exception e) {
            return List.of();
        }
    }

    private String buildSynthPrompt(StepExecutionContext context, List<ResearchToolResult> results) {
        StringBuilder sb = new StringBuilder();
        if (context.taskRun().getGoal() != null && !context.taskRun().getGoal().isBlank()) {
            sb.append("## 用户要求\n").append(context.taskRun().getGoal()).append("\n\n");
        }
        sb.append("## 检索结果\n");
        if (results.isEmpty()) {
            sb.append("（无检索结果，请基于已有材料给出分析与说明）\n");
        } else {
            for (ResearchToolResult result : results) {
                sb.append("### 查询: ").append(result.query()).append("\n");
                if (result.success()) {
                    sb.append(result.output() == null ? "" : result.output()).append("\n\n");
                } else {
                    sb.append("[检索失败] ").append(result.error() == null ? "unknown" : result.error()).append("\n\n");
                }
            }
        }
        return sb.toString();
    }
}
