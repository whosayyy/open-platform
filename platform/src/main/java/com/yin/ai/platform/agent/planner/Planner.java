package com.yin.ai.platform.agent.planner;

import com.yin.ai.platform.agent.AgentContext;

/**
 * Agent 规划器 SPI — 将用户目标分解为可执行步骤。
 * <p>
 * 实现策略:
 * <ul>
 *   <li>TemplatePlanner — 使用预定义模板（确定性任务）</li>
 *   <li>LLMPlanner — LLM 自主生成计划（开放性任务）</li>
 *   <li>HybridPlanner — 模板 + LLM 增强</li>
 * </ul>
 */
@FunctionalInterface
public interface Planner {

    /**
     * 根据 Agent 上下文生成执行计划。
     *
     * @param ctx Agent 执行上下文
     * @return 执行计划（空计划 = 直接返回 result 文本）
     */
    Plan plan(AgentContext ctx);
}
