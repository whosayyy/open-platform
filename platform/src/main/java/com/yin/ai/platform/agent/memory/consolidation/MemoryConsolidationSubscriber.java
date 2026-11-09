package com.yin.ai.platform.agent.memory.consolidation;

import com.yin.ai.platform.agent.event.AgentEvent;
import com.yin.ai.platform.agent.event.TaskEventType;
import com.yin.ai.platform.agent.memory.AgentMemoryBridge;
import com.yin.ai.platform.agent.pattern.AgentExecutionContext;
import com.yin.ai.platform.core.memory.MemoryCell;
import com.yin.ai.platform.core.memory.working.WorkingMemory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Set;

/**
 * 记忆整合事件订阅者 — 订阅 TASK_COMPLETED / TASK_FAILED 事件，异步触发整合。
 * <p>
 * 解耦：Pattern 不需要知道 Consolidation 的存在。
 * 由 EventPublisher 的事件分发机制调用。
 */
public class MemoryConsolidationSubscriber {

    private static final Logger log = LoggerFactory.getLogger(MemoryConsolidationSubscriber.class);

    private final MemoryConsolidationEngine consolidationEngine;
    private final AgentMemoryBridge memoryBridge;

    public MemoryConsolidationSubscriber(MemoryConsolidationEngine consolidationEngine,
                                          AgentMemoryBridge memoryBridge) {
        this.consolidationEngine = consolidationEngine;
        this.memoryBridge = memoryBridge;
    }

    public Set<TaskEventType> subscribedTypes() {
        return Set.of(TaskEventType.TASK_COMPLETED, TaskEventType.TASK_FAILED);
    }

    /**
     * 事件处理入口。
     */
    public void onEvent(AgentEvent event, AgentExecutionContext ctx) {
        if (ctx == null) return;

        if (event.type() == TaskEventType.TASK_COMPLETED) {
            handleCompletion(ctx);
        } else if (event.type() == TaskEventType.TASK_FAILED) {
            handleFailure(ctx);
        }
    }

    private void handleCompletion(AgentExecutionContext ctx) {
        var strategy = ctx.getVariable("memory.strategy",
                com.yin.ai.platform.agent.memory.strategy.ModeMemoryStrategy.class);
        if (strategy == null) {
            log.debug("[MEM-SUB] no memory strategy for taskId={}, skip", ctx.getTaskId());
            return;
        }

        var policy = strategy.consolidation();
        if (policy.equals(com.yin.ai.platform.agent.memory.strategy.ModeMemoryStrategy
                .ConsolidationPolicy.none())) {
            return;  // SIMPLE 模式不整合
        }

        // 读取 Timeline（通过 AgentMemoryBridge）
        List<MemoryCell> timeline = memoryBridge.readRecentTimeline(ctx.getAgentContext(), 100);
        consolidationEngine.consolidate(ctx, policy, timeline);
    }

    private void handleFailure(AgentExecutionContext ctx) {
        // 失败任务：保存 WorkingMemory + 创建 ResumePoint（已有逻辑）
        WorkingMemory wm = ctx.getVariable("memory.workingMemory", WorkingMemory.class);
        if (wm != null) {
            memoryBridge.onTaskFailure(wm, ctx.getAgentContext(),
                    new RuntimeException("Task failed: " + ctx.getTaskId()));
        }
    }
}
