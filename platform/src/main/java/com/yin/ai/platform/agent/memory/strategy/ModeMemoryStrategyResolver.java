package com.yin.ai.platform.agent.memory.strategy;

import com.yin.ai.platform.agent.pattern.AgentExecutionContext;
import com.yin.ai.platform.core.agent.mode.AgentMode;

/**
 * 模式记忆策略解析器 — 根据 AgentMode + 任务上下文解析最终策略。
 * <p>
 * 优先级：用户显式覆盖 > ModeMemoryStrategy > MemoryStrategySelector > AgentMemoryConfig
 */
public interface ModeMemoryStrategyResolver {

    ModeMemoryStrategy resolve(AgentMode mode, AgentExecutionContext ctx);

    /** 默认实现：直接映射 mode → 预置策略 */
    static ModeMemoryStrategyResolver defaultResolver() {
        return (mode, ctx) -> switch (mode) {
            case DIRECT -> ModeMemoryStrategy.simple();
            case REACT -> ModeMemoryStrategy.react();
            case PLAN_EXECUTE -> ModeMemoryStrategy.planExecute();
            case DEEP_RESEARCH -> ModeMemoryStrategy.deepResearch();
            case MULTI_AGENT -> ModeMemoryStrategy.deepResearch();
        };
    }
}
