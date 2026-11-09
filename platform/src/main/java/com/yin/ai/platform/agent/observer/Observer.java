package com.yin.ai.platform.agent.observer;

import com.yin.ai.platform.agent.AgentContext;
import com.yin.ai.platform.agent.executor.StepResult;

/**
 * Agent 观察器 SPI — 评估步骤执行结果。
 */
@FunctionalInterface
public interface Observer {

    /**
     * 观察步骤执行结果。
     *
     * @param result 步骤执行结果
     * @param ctx    Agent 执行上下文
     * @return 观察结论
     */
    Observation observe(StepResult result, AgentContext ctx);
}
