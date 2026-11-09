package com.yin.ai.platform.agent.pattern.research;

import com.yin.ai.platform.agent.pattern.AgentExecutionContext;
import com.yin.ai.platform.agent.pattern.ReasoningOptionsSupport;
import com.yin.ai.platform.agent.pattern.PlanJsonParser;
import com.yin.ai.platform.agent.pattern.PlanToolGenerator;
import com.yin.ai.platform.agent.pattern.TaskPlan;
import com.yin.ai.platform.agent.pattern.TaskStep;
import com.yin.ai.platform.agent.pattern.executor.StepExecutorDispatcher;
import com.yin.ai.platform.gateway.GatewayTextClient;
import com.yin.ai.platform.structured.StructuredClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 研究规划器 — DeepResearch 的第二阶段。
 * <p>
 * 与 PlanExecute 的 ConstrainedPlanner 共享"受约束规划"理念，
 * 但研究计划有其特殊性：
 * - 步骤围绕"搜索→提取→验证→综合"的研究循环组织
 * - 每步绑定具体的搜索策略（关键词、语言、来源类型）
 * - 计划是"初始版本"，执行中可由 Reflector 动态追加轮次
 * - 包含终止条件（最大轮次、最小信息量）
 * <p>
 * 约束输入：
 * - 可用 executor 清单（从 StepExecutorDispatcher 获取）
 * - token 预算
 * - 用户偏好（从 Memory 检索，后续接入）
 */
public class ResearchPlanner {

    private static final Logger log = LoggerFactory.getLogger(ResearchPlanner.class);
    private static final int DEFAULT_MAX_ROUNDS = 5;
    private static final int DEFAULT_STEPS_PER_ROUND = 3;

    private final GatewayTextClient chatClient;
    private final StructuredClient structuredClient;
    private final StepExecutorDispatcher dispatcher;

    public ResearchPlanner(GatewayTextClient chatClient, StructuredClient structuredClient,
                           StepExecutorDispatcher dispatcher) {
        this.chatClient = chatClient;
        this.structuredClient = structuredClient;
        this.dispatcher = dispatcher;
    }

    /**
     * 生成研究计划。
     *
     * @param clarifiedGoal 澄清后的研究目标
     * @param ctx           执行上下文
     * @return 研究计划（含搜索策略和验证标准）
     */
    public TaskPlan plan(String clarifiedGoal, AgentExecutionContext ctx) {
        // 工具调用式规划（opt-in）：模型按 submitPlan 工具的 schema 返回计划，结构由模型层保证
        if (PlanToolGenerator.enabled()) {
            String toolPrompt = buildPlanningPrompt(clarifiedGoal, ctx, true);
            String systemPrompt = ctx.getVariable("systemPrompt") instanceof String s && !s.isBlank() ? s : null;
            Double temp = ctx.getVariable("temperature") instanceof Double t ? t : null;
            Optional<TaskPlan> viaTool = PlanToolGenerator.generate(structuredClient, clarifiedGoal, toolPrompt,
                    systemPrompt, temp, executor -> dispatcher.isAvailable(executor));
            if (viaTool.isPresent()) {
                log.info("[RESEARCH-PLAN] taskId={} plan via tool-call: {} steps",
                        ctx.getTaskId(), viaTool.get().totalSteps());
                return viaTool.get();
            }
            log.warn("[RESEARCH-PLAN] tool-based planning failed, falling back to prompt+JSON");
        }

        String prompt = buildPlanningPrompt(clarifiedGoal, ctx, false);
        try {
            String response = callWithConfig(ctx, prompt);
            TaskPlan plan = parseResearchPlan(response, clarifiedGoal);
            if (plan != null) {
                log.info("[RESEARCH-PLAN] taskId={} generated {} steps",
                        ctx.getTaskId(), plan.totalSteps());
                return plan;
            }
            log.warn("[RESEARCH-PLAN] LLM plan unparseable, using default template");
            return defaultResearchPlan(clarifiedGoal);
        } catch (Exception e) {
            log.warn("[RESEARCH-PLAN] LLM planning failed, using default template: {}", e.getMessage());
            return defaultResearchPlan(clarifiedGoal);
        }
    }

    private String buildPlanningPrompt(String goal, AgentExecutionContext ctx, boolean toolMode) {
        String formatRule = toolMode
                ? "调用 submitPlan 工具提交计划（工具参数即为计划，不要输出任何文字）。"
                : "只输出 JSON 数组本身，不要包含代码块或解释文字。";
        String prompt = """
                你是一个研究规划器。根据研究目标生成结构化的研究计划。

                研究目标：%s

                可用执行器：
                - llm：LLM 分析/生成
                - web_search：网络搜索（后续接入）
                - rag_search：检索企业知识库（RAG），适合需要内部知识/资料的问题
                - view_artifact：读取已保存的文章/产物全文（会话上下文中带引用 URL 时使用）

                请生成研究计划，围绕"搜集 → 分析 → 验证 → 综合"研究循环，但步骤必须粗粒度：
                1. 步骤数 3-5 步，粒度要粗：相关联的工作必须合并为一步
                   （例如「先搜索、再验证」合并为一步「搜索并验证」；「提取信息、识别模式」合并为一步「分析提炼」）
                2. 每步 description 必须是简短的动作总结（一句话，15 字以内），说明这一步要完成什么
                3. 每步绑定执行器（llm / web_search / rag_search）与依赖（dependsOn，尽量少，优先线性推进）
                4. 规划目标：每个步骤将来可以独立派发给一个子 Agent 执行（multi-agent 扩展），
                   所以每步要自包含、边界清晰
                5. 若用户目标明确需要最新/实时信息，第一步必须用 web_search 搜索

                每步输出格式：
                - id: 步骤标识（英文）
                - description: 简短动作总结
                - executor: 执行器（llm / web_search / rag_search）
                - dependsOn: 依赖步骤
                - [B-agentic Memory] requiresMemory: 默认 false。仅当此步骤必须参考用户历史偏好、
                  过往相似案例、过往对话要点时才置 true。ReActLoop 任务级已做一次记忆召回，普通研究步骤不填 false，
                  避免 token 浪费与噪声。

                要求：%s id 唯一简短；dependsOn 只能引用已定义的步骤 id。
                执行中如某步骤需要额外历史记忆，LLM 可运行时调用 recallMemory 工具按需召回。
                """.formatted(goal, formatRule);
        Object sessionContext = ctx.getVariable("sessionContext");
        if (sessionContext != null && !sessionContext.toString().isBlank()) {
            prompt = sessionContext + "\n\n" + prompt;
        }
        return prompt;
    }

    private String callWithConfig(AgentExecutionContext ctx, String userPrompt) {
        String sp = ctx.getVariable("systemPrompt") instanceof String s && !s.isBlank() ? s : null;
        Double temp = ctx.getVariable("temperature") instanceof Double t ? t : null;
        var executionOptions = ctx.getVariable("modelExecutionOptions")
                instanceof com.yin.ai.platform.gateway.ModelExecutionOptions value ? value : null;
        return chatClient.generate("agent:research-planner", userPrompt, sp, temp, executionOptions)
                .getContent();
    }

    TaskPlan parseResearchPlan(String response, String goal) {
        return PlanJsonParser.parse(response, goal, executor -> dispatcher.isAvailable(executor));
    }

    /**
     * 默认研究计划模板 — 当 LLM 规划失败时的 fallback。
     * 粗粒度、简短总结：相关活动合并为一步（搜索并验证 → 分析提炼 → 综合分析 → 生成报告），
     * 每步自包含、边界清晰，便于后续 multi-agent 拆分派发。
     */
    private TaskPlan defaultResearchPlan(String goal) {
        List<TaskStep> steps = new ArrayList<>();

        // 1. 搜集 + 验证（合并原 search-broad / search-specific / verify 的验证职责）
        steps.add(TaskStep.pending("research",
                "搜索并验证关键信息", "web_search", List.of()));

        // 2. 分析（合并原 search-specific / extract）
        steps.add(TaskStep.pending("analyze",
                "分析提炼核心信息", "llm", List.of("research")));

        // 3. 综合（合并原 verify / synthesize）
        steps.add(TaskStep.pending("synthesize",
                "综合分析并交叉验证", "llm", List.of("analyze")));

        // 4. 报告（原 report）
        steps.add(TaskStep.pending("report",
                "生成研究报告", "llm", List.of("synthesize")));

        return new TaskPlan(goal, steps);
    }
}
