package com.yin.ai.platform.agent.reflector;

import com.yin.ai.platform.agent.AgentContext;
import com.yin.ai.platform.agent.observer.Observation;

/**
 * Agent 反思器 SPI — 基于观察结果决定下一步行动。
 */
@FunctionalInterface
public interface Reflector {

    /**
     * 反思并决定下一步。
     *
     * @param observation 当前步骤的观察结论
     * @param ctx         Agent 执行上下文
     * @return 决策
     */
    Decision reflect(Observation observation, AgentContext ctx);
}
