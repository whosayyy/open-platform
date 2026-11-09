package com.yin.ai.platform.agent.executor;

import com.yin.ai.platform.agent.AgentContext;
import com.yin.ai.platform.agent.planner.Step;

/**
 * Agent 执行器 SPI — 执行单个步骤（调用 Tool 或 LLM）。
 */
@FunctionalInterface
public interface AgentExecutor {

    /**
     * 执行单个步骤。
     *
     * @param step 待执行的步骤
     * @param ctx  Agent 执行上下文
     * @return 步骤执行结果
     */
    StepResult execute(Step step, AgentContext ctx);
}
