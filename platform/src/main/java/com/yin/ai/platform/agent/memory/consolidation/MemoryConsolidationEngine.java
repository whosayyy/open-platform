package com.yin.ai.platform.agent.memory.consolidation;

import com.yin.ai.platform.agent.memory.strategy.ModeMemoryStrategy.ConsolidationPolicy;
import com.yin.ai.platform.agent.pattern.AgentExecutionContext;
import com.yin.ai.platform.core.memory.MemoryCell;

import java.util.List;

/**
 * 任务后记忆整合引擎 SPI。
 * 由 TASK_COMPLETED 事件异步触发，不阻塞主流程。
 * <p>
 * 职责：从执行时间线中提炼长期有价值的知识（Semantic）和可复用流程（Procedural）。
 */
public interface MemoryConsolidationEngine {

    /**
     * 从任务执行历史中提炼长期记忆。
     *
     * @param execCtx  已完成任务的执行上下文
     * @param policy   整合策略
     * @param timeline 本次任务的 Episodic Timeline
     * @return 整合结果
     */
    ConsolidationResult consolidate(AgentExecutionContext execCtx,
                                     ConsolidationPolicy policy,
                                     List<MemoryCell> timeline);
}
