package com.yin.ai.platform.core.agent.mode;

import com.fasterxml.jackson.annotation.JsonCreator;

/**
 * Legacy orchestration mode enum — five orchestration modes that coexist in the
 * compatibility layer.
 * <p>
 * This is distinct from {@link com.yin.ai.platform.core.agent.pattern.AgentMode}, which is the
 * canonical execution mode set used by the Core Agent Runtime and covers four modes
 * (DIRECT / REACT / PLAN_EXECUTE / DEEP_RESEARCH). This enum additionally carries
 * {@code MULTI_AGENT}, which the legacy orchestration layer handles itself.
 * <p>
 * It lives in the core module so that the AI gateway and other lower layers can refer to
 * orchestration modes without depending on the legacy {@code agent} package.
 */
public enum AgentMode {

    /** 单次 LLM 调用，无工具、无多步。适用于简单问答。 */
    DIRECT,

    /** ReAct Loop：思考→行动→观察→反思循环。适用于需要工具调用的任务。 */
    REACT,

    /** 规划-执行：先生成多步计划，再逐步执行。适用于复杂创作/分析任务。 */
    PLAN_EXECUTE,

    /** 深度研究：多轮搜索→阅读→综合→验证。适用于需要深度调研的任务。 */
    DEEP_RESEARCH,

    /** Multi-agent team orchestration with isolated child tasks. */
    MULTI_AGENT;

    /**
     * 从配置字符串解析，兼容前端传参。
     * 无法识别时默认返回 REACT（最通用的模式）。
     */
    @JsonCreator
    public static AgentMode fromString(String s) {
        if (s == null || s.isBlank()) return REACT;
        return switch (s.toLowerCase().trim()) {
            case "simple", "chat", "direct" -> DIRECT;
            case "react", "tool" -> REACT;
            case "plan", "plan_execute", "plan-execute" -> PLAN_EXECUTE;
            case "deep_research", "research", "deep" -> DEEP_RESEARCH;
            case "multi_agent", "multi-agent", "team", "team_research" -> MULTI_AGENT;
            default -> REACT;
        };
    }

    /** 是否支持中断恢复 */
    public boolean resumable() {
        return this == REACT || this == PLAN_EXECUTE || this == DEEP_RESEARCH || this == MULTI_AGENT;
    }
}
