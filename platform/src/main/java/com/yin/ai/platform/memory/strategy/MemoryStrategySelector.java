package com.yin.ai.platform.memory.strategy;

import com.yin.ai.platform.agent.AgentContext;
import com.yin.ai.platform.agent.strategy.ComplexityAssessment;

/**
 * 记忆策略选择器 SPI — 根据任务复杂度和上下文动态选择需要加载的 MemoryType。
 */
public interface MemoryStrategySelector {

    MemoryTypeDecision select(AgentContext ctx, ComplexityAssessment complexity);

    /** 优先级，值越大越优先。 */
    default int priority() {
        return 0;
    }

    default String name() {
        return getClass().getSimpleName();
    }
}
