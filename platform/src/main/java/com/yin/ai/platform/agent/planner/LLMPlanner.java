package com.yin.ai.platform.agent.planner;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yin.ai.platform.agent.AgentContext;
import com.yin.ai.platform.agent.StructuredOutputContract;
import com.yin.ai.platform.agent.tool.ToolSelectionPolicy;
import com.yin.ai.platform.function.FunctionDefinition;
import com.yin.ai.platform.function.FunctionRegistry;
import com.yin.ai.platform.gateway.GatewayMessage;
import com.yin.ai.platform.gateway.GatewayOptions;
import com.yin.ai.platform.gateway.GatewayRequest;
import com.yin.ai.platform.gateway.GatewayResponse;
import com.yin.ai.platform.gateway.UnifiedChatClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * LLM 动态规划器 — 通过 LLM 自主将用户目标拆解为执行步骤。
 * <p>
 * 调用 UnifiedChatClient 发送规划 Prompt，使用 PlanResponse DTO 反序列化 LLM 返回的 JSON。
 * 解析失败时降级为 Plan.empty()，不抛异常。
 * <p>
 * Prompt 策略可通过 {@link PlannerPromptStrategy} 注入，默认使用通用 JSON 步骤规划策略。
 */
public class LLMPlanner implements Planner {

    private static final Logger log = LoggerFactory.getLogger(LLMPlanner.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final UnifiedChatClient chatClient;
    private final FunctionRegistry functionRegistry;
    private final PlannerPromptStrategy promptStrategy;
    private final ToolSelectionPolicy toolSelectionPolicy;

    public LLMPlanner(UnifiedChatClient chatClient) {
        this(chatClient, null, PlannerPromptStrategy.defaults());
    }

    public LLMPlanner(UnifiedChatClient chatClient, FunctionRegistry functionRegistry) {
        this(chatClient, functionRegistry, PlannerPromptStrategy.defaults());
    }

    public LLMPlanner(UnifiedChatClient chatClient, FunctionRegistry functionRegistry,
                      PlannerPromptStrategy promptStrategy) {
        this(chatClient, functionRegistry, promptStrategy, null);
    }

    public LLMPlanner(UnifiedChatClient chatClient, FunctionRegistry functionRegistry,
                      PlannerPromptStrategy promptStrategy, ToolSelectionPolicy toolSelectionPolicy) {
        this.chatClient = chatClient;
        this.functionRegistry = functionRegistry;
        this.promptStrategy = promptStrategy != null ? promptStrategy : PlannerPromptStrategy.defaults();
        this.toolSelectionPolicy = toolSelectionPolicy;
    }

    @Override
    public Plan plan(AgentContext ctx) {
        String goal = ctx.getTask().goal();
        Collection<FunctionDefinition> definitions = getAvailableTools(ctx);
        StructuredOutputContract contract = ctx.getConfig().outputContract();

        GatewayRequest request = new GatewayRequest();
        List<GatewayMessage> messages = new ArrayList<>();
        messages.add(GatewayMessage.system(promptStrategy.buildSystemPrompt()));
        Object ragContext = ctx.getVariable("agent.ragContext");
        if (ragContext instanceof String evidence && !evidence.isBlank()) {
            messages.add(GatewayMessage.system("【知识库证据约束】规划和直接答案只能使用以下证据。"
                    + "直接答案中的事实结论必须引用 [E编号]；无证据支持时不得补充模型记忆。\n"
                    + evidence));
        }
        messages.add(GatewayMessage.user(promptStrategy.buildUserMessage(goal, definitions)));
        if (contract != null) {
            // 结构化输出契约：注册 capture 工具但不强制 tool_choice，模型可自行决策
            // 直接提交结果（submitResult 调用）或规划研究步骤（JSON 计划）
            messages.add(GatewayMessage.system(buildContractInstruction(contract)));
        }
        request.setMessages(messages);
        request.setCaller("agent.planner");

        GatewayOptions options = new GatewayOptions();
        options.setTemperature(0.3);
        options.setEnableFunctionCalling(false);
        if (contract != null) {
            options.setJsonResponse(false);
            options.setForcedToolName(contract.toolName());
            options.setOutputSchema(contract.jsonSchema());
        } else {
            options.setJsonResponse(ctx.getConfig().jsonResponse());
        }
        request.setOptions(options);

        try {
            GatewayResponse response = chatClient.chat(request);
            // 结构化输出契约：优先取工具调用参数（模型直接提交最终结果）
            if (contract != null && response.getToolCallArguments() != null
                    && !response.getToolCallArguments().isBlank()) {
                log.info("[LLM-PLANNER] structured output via {}, direct result", contract.toolName());
                return Plan.direct(response.getToolCallArguments());
            }
            String content = response.getContent();
            if (content == null || content.isBlank()) {
                log.warn("[LLM-PLANNER] empty response, returning empty plan");
                return Plan.empty();
            }
            return parseResponse(content);
        } catch (Exception e) {
            log.error("[LLM-PLANNER] LLM call failed: {}", e.getMessage(), e);
            return Plan.error("LLM 调用失败: " + e.getMessage());
        }
    }

    private static String buildContractInstruction(StructuredOutputContract contract) {
        return "【输出契约】如果信息已足够，直接调用 " + contract.toolName()
                + " 工具提交最终结果（工具参数即为结果 JSON）；否则规划研究步骤，最后一步再提交。"
                + "不要输出任何文字或 Markdown。";
    }

    private Plan parseResponse(String content) {
        String json = extractJson(content);
        if (json == null) {
            log.warn("[LLM-PLANNER] no JSON found in response, degrading to empty plan. contentLen={}", content.length());
            return Plan.empty();
        }
        try {
            // 提取到的是 JSON 数组（如 [8,5,9,3]）：说明 LLM 直接输出了 goal 要求的格式，
            // 包装为 {"items": [...]} 返回给下游消费者，避免 LinkedHashMap 反序列化失败
            String trimmed = json.trim();
            if (trimmed.startsWith("[")) {
                log.info("[LLM-PLANNER] extracted JSON array, wrapping as direct result. len={}", trimmed.length());
                return Plan.direct("{\"items\":" + trimmed + "}");
            }

            PlanResponse response = mapper.readValue(json, PlanResponse.class);

            if (response.hasDirect()) {
                return Plan.direct(response.direct());
            }
            if (response.isEmpty()) {
                // The LLM returned valid JSON but not in the planner's format
                // (no "direct" field, no "steps" array). This commonly happens
                // when jsonResponse=true and the LLM outputs the goal's target
                // format directly (e.g. {items: [...]}) instead of wrapping it
                // in the planner envelope. Treat the raw content as the result
                // rather than silently dropping it.
                log.warn("[LLM-PLANNER] response has no direct/steps fields, using raw content as direct result. contentLen={}",
                        content.length());
                return Plan.direct(content);
            }

            List<Step> steps = new ArrayList<>();
            for (PlanResponse.StepDef def : response.steps()) {
                if (def.id() == null || def.description() == null) {
                    log.warn("[LLM-PLANNER] skipping step with missing id/description: id={} desc={}",
                            def.id(), def.description());
                    continue;
                }
                steps.add(def.toStep());
            }

            if (steps.isEmpty()) {
                log.warn("[LLM-PLANNER] all steps filtered out: {}", content);
                return Plan.empty();
            }
            return Plan.ofSteps(steps);
        } catch (Exception e) {
            // The LLM returned valid JSON (jsonResponse=true enforced) but with an
            // unexpected structure — e.g. a JSON object as the "direct" value instead
            // of a string. Rather than discarding the output, treat the raw content
            // as the result and let downstream code (wrapAgentResult) parse it.
            log.warn("[LLM-PLANNER] JSON parse failed: {} contentLen={}", e.getMessage(), content.length());
            return Plan.direct(content);
        }
    }

    /**
     * 从 LLM 原始输出中提取 JSON 片段。
     * 兼容纯 JSON（对象/数组）和 ```json ... ``` 代码块包裹格式。
     */
    static String extractJson(String content) {
        String trimmed = content.trim();

        // 尝试匹配 ```json ... ``` 代码块
        int fenceStart = trimmed.indexOf("```json");
        if (fenceStart >= 0) {
            int jsonStart = trimmed.indexOf('{', fenceStart);
            if (jsonStart < 0) jsonStart = trimmed.indexOf('[', fenceStart);
            int fenceEnd = trimmed.indexOf("```", jsonStart > 0 ? jsonStart : fenceStart + 7);
            if (jsonStart >= 0 && fenceEnd > jsonStart) {
                return trimmed.substring(jsonStart, fenceEnd).trim();
            }
        }

        // 纯 JSON：提取最外层 {} 或 []
        int objStart = trimmed.indexOf('{');
        int arrStart = trimmed.indexOf('[');
        int objEnd = trimmed.lastIndexOf('}');
        int arrEnd = trimmed.lastIndexOf(']');

        if (objStart >= 0 && objEnd > objStart) {
            return trimmed.substring(objStart, objEnd + 1);
        }
        if (arrStart >= 0 && arrEnd > arrStart) {
            return trimmed.substring(arrStart, arrEnd + 1);
        }
        // No JSON brackets found — not JSON at all
        return null;
    }

    private Collection<FunctionDefinition> getAvailableTools(AgentContext ctx) {
        Collection<FunctionDefinition> all;
        if (functionRegistry != null) {
            all = functionRegistry.getAllDefinitions();
        } else {
            @SuppressWarnings("unchecked")
            Collection<FunctionDefinition> tools =
                    ctx.getVariable("availableTools", Collection.class);
            all = tools != null ? tools : Collections.emptyList();
        }

        // toolFilter 白名单：只把白名单内的工具暴露给规划器，避免规划出无法执行的步骤
        @SuppressWarnings("unchecked")
        Set<String> filter = ctx.getVariable("toolFilter", Set.class);
        Collection<FunctionDefinition> authorized = all;
        if (filter != null && !filter.isEmpty()) {
            authorized = all.stream().filter(d -> filter.contains(d.getName())).toList();
        }
        if (toolSelectionPolicy == null) return authorized;
        @SuppressWarnings("unchecked")
        Set<String> required = ctx.getVariable("requiredTools", Set.class);
        return toolSelectionPolicy.select(ctx.getTask().goal(), authorized,
                filter != null ? filter : Set.of(), required != null ? required : Set.of(), ctx).tools();
    }
}
