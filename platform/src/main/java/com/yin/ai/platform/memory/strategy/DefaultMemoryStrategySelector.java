package com.yin.ai.platform.memory.strategy;

import com.yin.ai.platform.agent.AgentContext;
import com.yin.ai.platform.agent.strategy.ComplexityAssessment;
import com.yin.ai.platform.agent.strategy.ComplexityLevel;

/**
 * 默认记忆策略选择器 — 根据任务复杂度等级选择 MemoryType。
 * <p>
 * 决策规则：
 * <ul>
 *   <li>SIMPLE → 仅 Session 记忆（最近对话历史），recallTopK=3</li>
 *   <li>MODERATE → WorkingMemory + Episodic，recallTopK=5</li>
 *   <li>COMPLEX → 全部 MemoryType，recallTopK=10</li>
 * </ul>
 */
public class DefaultMemoryStrategySelector implements MemoryStrategySelector {

    @Override
    public MemoryTypeDecision select(AgentContext ctx, ComplexityAssessment complexity) {
        if (complexity == null) {
            return MemoryTypeDecision.sessionOnly(3);
        }

        return switch (complexity.level()) {
            case SIMPLE -> MemoryTypeDecision.sessionOnly(3);
            case MODERATE -> MemoryTypeDecision.moderate(5);
            case COMPLEX -> MemoryTypeDecision.full(10);
        };
    }

    @Override
    public int priority() {
        return 100;
    }
}
