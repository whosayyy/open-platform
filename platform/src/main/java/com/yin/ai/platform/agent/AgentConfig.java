package com.yin.ai.platform.agent;

import com.yin.ai.platform.agent.planner.PlanningMode;

import java.time.Duration;

/**
 * Agent 运行时配置。
 *
 * @param maxSteps          最大步骤数，防止无限循环（默认 12，平台硬上限 50）
 * @param timeout           整体超时（默认 30 分钟）
 * @param maxRetriesPerStep 每步重试上限（默认 3）
 * @param enableHitl        是否启用人机协同检查点（默认 false）
 * @param maxSubAgentDepth  SubAgent 嵌套深度上限（默认 3）
 * @param planningMode      规划模式（默认 TEMPLATE）
 * @param jsonResponse      是否强制 LLM 输出 JSON（设置 response_format = json_object）
 * @param outputContract    结构化输出契约（可空；非空时以工具 JSON Schema 替代提示词限定输出）
 */
public record AgentConfig(
        int maxSteps,
        Duration timeout,
        int maxRetriesPerStep,
        boolean enableHitl,
        int maxSubAgentDepth,
        PlanningMode planningMode,
        boolean jsonResponse,
        StructuredOutputContract outputContract) {

    public static final int DEFAULT_MAX_STEPS = 12;
    public static final int PLATFORM_MAX_STEPS = 50;

    public AgentConfig {
        maxSteps = maxSteps <= 0 ? DEFAULT_MAX_STEPS : Math.min(maxSteps, PLATFORM_MAX_STEPS);
        timeout = timeout != null ? timeout : Duration.ofMinutes(30);
        maxRetriesPerStep = Math.max(0, maxRetriesPerStep);
        maxSubAgentDepth = Math.max(0, maxSubAgentDepth);
        planningMode = planningMode != null ? planningMode : PlanningMode.TEMPLATE;
    }

    /** 兼容旧 7 参构造：无结构化输出契约。 */
    public AgentConfig(int maxSteps, Duration timeout, int maxRetriesPerStep,
                       boolean enableHitl, int maxSubAgentDepth,
                       PlanningMode planningMode, boolean jsonResponse) {
        this(maxSteps, timeout, maxRetriesPerStep, enableHitl, maxSubAgentDepth,
                planningMode, jsonResponse, null);
    }

    public static AgentConfig defaults() {
        return new AgentConfig(DEFAULT_MAX_STEPS, Duration.ofMinutes(30), 3, false, 3,
                PlanningMode.TEMPLATE, false);
    }

    public static AgentConfig of(int maxSteps, Duration timeout) {
        return new AgentConfig(maxSteps, timeout, 3, false, 3, PlanningMode.TEMPLATE, false);
    }

    public static AgentConfig of(int maxSteps, Duration timeout, boolean jsonResponse) {
        return new AgentConfig(maxSteps, timeout, 3, false, 3, PlanningMode.TEMPLATE, jsonResponse);
    }

    /** 结构化输出模式：以工具 JSON Schema 契约替代 jsonResponse 提示词限定。 */
    public static AgentConfig of(int maxSteps, Duration timeout, StructuredOutputContract contract) {
        return new AgentConfig(maxSteps, timeout, 3, false, 3, PlanningMode.TEMPLATE, false, contract);
    }
}
