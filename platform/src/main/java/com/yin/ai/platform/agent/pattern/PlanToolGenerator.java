package com.yin.ai.platform.agent.pattern;

import com.yin.ai.platform.structured.StructuredClient;
import com.yin.ai.platform.structured.StructuredToolGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.tool.ToolCallback;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * 基于工具调用（function calling）的结构化计划生成器 — 阶段三的 tool-call 原型。
 * <p>
 * 设计动机：当前计划生成靠"提示词限定 LLM 输出 JSON"，模型可能返回代码块/散文/缺字段，
 * 依赖 {@link PlanJsonParser} 做容错解析。工具调用方式把"计划结构"上升为工具的 JSON Schema
 * 契约，模型必须按 schema 调用 {@code submitPlan} 工具并携带完整参数，结构由模型层保证，
 * 更符合企业级 Agent 平台的约束方式。
 * <p>
 * 关键机制：
 * <ul>
 *   <li><b>capture-only</b>：{@code submitPlan} 工具只"捕获"参数、返回空标记，不产生副作用。</li>
 *   <li><b>强制工具</b>：通过 {@code tool_choice={"type":"function","function":{"name":"submitPlan"}}}
 *       强制模型单轮直接产出工具调用。</li>
 *   <li><b>禁止内部执行</b>：{@code internalToolExecutionEnabled(false)} 让 Spring AI 不执行工具、
 *       不追加第二轮对话，把工具调用参数原样暴露在响应中。</li>
 *   <li><b>回退</b>：任何失败（模型不支持、返回文本、schema 报错）返回 {@link Optional#empty()}，
 *       调用方回退到原有的"提示词 + {@link PlanJsonParser}"路径。</li>
 * </ul>
 * 通用 capture-only 机制已收敛到 {@link StructuredToolGenerator}，本类委托并保留计划 Schema。
 */
public final class PlanToolGenerator {

    private static final Logger log = LoggerFactory.getLogger(PlanToolGenerator.class);

    /** 工具开关：-Dwkc.agent.planner.toolCall=true 启用（默认关闭，原型 opt-in）。 */
    private static final String ENABLE_PROPERTY = "wkc.agent.planner.toolCall";

    public static final String TOOL_NAME = "submitPlan";

    private static final String TOOL_DESCRIPTION = "提交任务执行计划。当收到规划任务时必须调用本工具，"
            + "把完整计划作为工具参数返回，不要输出任何文字。";

    private PlanToolGenerator() {}

    /** 是否启用工具调用式规划（opt-in，避免未经评估改变线上行为）。 */
    public static boolean enabled() {
        return Boolean.parseBoolean(System.getProperty(ENABLE_PROPERTY, "false"));
    }

    /**
     * 通过工具调用生成执行计划。
     *
     * @param chatClient         LLM 客户端
     * @param goal               任务目标
     * @param userPrompt         规划提示词（含可用执行器约束）
     * @param systemPrompt       系统提示词（可空）
     * @param temperature        温度（可空，缺省用 0.2）
     * @param availableExecutor  executor 可用性判定（与 PlanJsonParser 共用）
     * @return 净化后的计划；工具调用失败/不可用时返回 {@link Optional#empty()}
     */
    public static Optional<TaskPlan> generate(StructuredClient chatClient, String goal, String userPrompt,
                                              String systemPrompt, Double temperature,
                                              Predicate<String> availableExecutor) {
        if (chatClient == null || goal == null || userPrompt == null) {
            return Optional.empty();
        }
        try {
            Optional<String> args = chatClient.generate(TOOL_NAME, TOOL_DESCRIPTION, planSchema(),
                    userPrompt, systemPrompt, temperature);
            Optional<TaskPlan> plan = args.flatMap(json -> parsePlanArguments(json, goal, availableExecutor));
            if (plan.isEmpty()) {
                log.warn("[PLAN-TOOL] no usable submitPlan tool-call in response");
            }
            return plan;
        } catch (Exception e) {
            log.warn("[PLAN-TOOL] tool-call planning failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 原始工具调用请求：强制 submitPlan 工具 + 禁用内部执行，返回模型原始响应。
     * 供 {@link #generate} 使用；测试可借此检查模型的原始输出（文本 / 工具调用）。
     */
    /**
     * 构造 capture-only 的 submitPlan 工具。内部执行被禁用，call() 不会被模型调用；
     * 即使被调用也只返回空标记，无副作用。
     */
    public static ToolCallback submitPlanTool() {
        return StructuredToolGenerator.captureTool(TOOL_NAME, TOOL_DESCRIPTION, planSchema());
    }

    /** 从模型响应中提取 submitPlan 工具调用的参数 JSON（委托 {@link StructuredToolGenerator}）。 */
    static Optional<String> extractToolCallArguments(ChatResponse response, String toolName) {
        return StructuredToolGenerator.extractToolCallArguments(response, toolName);
    }

    /** 解析工具参数 JSON 为执行计划；结构非法时返回空。复用 PlanJsonParser 的净化逻辑。 */
    static Optional<TaskPlan> parsePlanArguments(String argsJson, String goal,
                                                 Predicate<String> availableExecutor) {
        if (argsJson == null || argsJson.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(PlanJsonParser.parse(argsJson, goal, availableExecutor));
        } catch (Exception e) {
            log.warn("[PLAN-TOOL] parse plan arguments failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /** 计划契约对应的 JSON Schema（goal + steps[]，结构与 PlanJsonParser 的 JsonStep 对齐）。 */
    static Map<String, Object> planSchema() {
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("type", "object");
        Map<String, Object> stepProps = new LinkedHashMap<>();
        stepProps.put("id", field("string", "步骤唯一标识，英文/数字/连字符"));
        stepProps.put("description", field("string", "简短动作总结（一句话，15 字以内），描述这一步要完成的目标；相关联工作合并为一步"));
        stepProps.put("executor", field("string", "执行器，只能使用提示词中给出的可用执行器之一"));
        stepProps.put("dependsOn", Map.of(
                "type", "array",
                "description", "依赖的步骤 id 列表（可空数组）",
                "items", Map.of("type", "string")));
        // [B-agentic Memory] LLM 显式 opt-in 字段：默认 false（节省 token + 减噪声）
        stepProps.put("requiresMemory", Map.of(
                "type", "boolean",
                "description", "[agentic Memory] 是否需要自动注入历史记忆。默认 false：调研/分析/撰写步骤不填；仅在与用户偏好/历史强相关的步骤置 true"));
        step.put("properties", stepProps);
        step.put("required", List.of("id", "description", "executor", "dependsOn"));

        Map<String, Object> steps = new LinkedHashMap<>();
        steps.put("type", "array");
        steps.put("description", "执行步骤列表，3-5 步，粒度粗（相关联工作合并），每步可独立派发给子 Agent 执行");
        steps.put("items", step);

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("goal", field("string", "任务目标，必须与用户输入一致"));
        properties.put("steps", steps);

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("type", "object");
        root.put("properties", properties);
        root.put("required", List.of("goal", "steps"));
        return root;
    }

    private static Map<String, Object> field(String type, String description) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        m.put("description", description);
        return m;
    }
}
