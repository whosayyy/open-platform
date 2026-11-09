package com.yin.ai.platform.agent.pattern.reflect;

import com.yin.ai.platform.agent.pattern.AgentExecutionContext;
import com.yin.ai.platform.agent.pattern.TaskStep;

/**
 * 步骤反思器 SPI — 每步执行后评估输出质量，决定下一步行动。
 * <p>
 * 企业级 PlanExecute / DeepResearch 的核心差异点：
 * 不是" blindly 执行完所有步骤"，而是每步后都有质量门控。
 * <p>
 * 实现方式：
 * - LlmStepReflector：用 LLM 评估（灵活但有成本）
 * - RuleStepReflector：用规则评估（零成本但粗糙）
 * - CompositeStepReflector：规则先行 + LLM 兜底
 */
public interface StepReflector {

    /**
     * 评估步骤执行结果。
     *
     * @param step   刚执行完的步骤
     * @param output 步骤输出
     * @param ctx    执行上下文（含完整 Plan 和历史）
     * @return 反思结果（决策 + 理由 + 可能的计划变更）
     */
    ReflectResult reflect(TaskStep step, String output, AgentExecutionContext ctx);
}
