package com.yin.ai.platform.agent.memory;

import com.yin.ai.platform.agent.event.AgentEvent;
import com.yin.ai.platform.agent.event.TaskEventType;
import com.yin.ai.platform.agent.memory.consolidation.MemoryConsolidationSubscriber;
import com.yin.ai.platform.agent.memory.strategy.ModeMemoryStrategy;
import com.yin.ai.platform.agent.memory.strategy.ModeMemoryStrategyResolver;
import com.yin.ai.platform.agent.pattern.AgentExecutionContext;
import com.yin.ai.platform.engine.ExecutionContext;
import com.yin.ai.platform.engine.ExecutionContextHolder;
import com.yin.ai.platform.core.memory.api.QueryResult;
import com.yin.ai.platform.core.memory.working.WorkingMemory;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ExecutorService;

/** Wires mode-aware memory into the unified runtime lifecycle. */
@Slf4j
public class AgentMemoryLifecycle {

    private final AgentMemoryBridge bridge;
    private final ModeMemoryStrategyResolver resolver;
    private final MemoryConsolidationSubscriber consolidationSubscriber;
    private final ExecutorService consolidationExecutor;

    public AgentMemoryLifecycle(AgentMemoryBridge bridge,
                                ModeMemoryStrategyResolver resolver,
                                MemoryConsolidationSubscriber consolidationSubscriber,
                                ExecutorService consolidationExecutor) {
        this.bridge = bridge;
        this.resolver = resolver;
        this.consolidationSubscriber = consolidationSubscriber;
        this.consolidationExecutor = consolidationExecutor;
    }

    public void begin(AgentExecutionContext ctx) {
        ModeMemoryStrategy strategy = resolver.resolve(ctx.getAgentMode(), ctx);
        ctx.putVariable("memory.strategy", strategy);
        if (strategy.workingMemoryEnabled()) {
            WorkingMemory memory = bridge.beforeTask(ctx.getAgentContext());
            if (memory != null) ctx.putVariable("memory.workingMemory", memory);
        }
        QueryResult recall = bridge.recall(ctx.getGoal(), ctx.getAgentContext(), strategy.recallTopK(),
                strategy.recallTypes().toArray(new com.yin.ai.platform.core.memory.MemoryType[0]));
        if (recall != null) ctx.putVariable("memory.recall", recall);
    }

    public void recordStep(AgentExecutionContext ctx, String output) {
        ModeMemoryStrategy strategy = ctx.getVariable("memory.strategy", ModeMemoryStrategy.class);
        if (strategy == null || !strategy.episodicPerStep()) return;
        WorkingMemory memory = ctx.getVariable("memory.workingMemory", WorkingMemory.class);
        try {
            bridge.recordPatternStep(memory, ctx.getCurrentStepId(), output, ctx.getAgentContext());
        } catch (RuntimeException e) {
            log.warn("[MEMORY] step recording failed taskId={} stepId={}: {}",
                    ctx.getTaskId(), ctx.getCurrentStepId(), e.getMessage());
        }
    }

    public void complete(AgentExecutionContext ctx) {
        WorkingMemory memory = ctx.getVariable("memory.workingMemory", WorkingMemory.class);
        try {
            bridge.afterTask(memory, ctx.getAgentContext(), finalOutput(ctx));
        } catch (RuntimeException e) {
            log.warn("[MEMORY] task finalization failed taskId={}: {}", ctx.getTaskId(), e.getMessage());
        }
        dispatch(new AgentEvent(null, ctx.getTaskId(), TaskEventType.TASK_COMPLETED,
                ctx.getCurrentStepId(), Map.of(), Instant.now()), ctx);
    }

    public void fail(AgentExecutionContext ctx) {
        dispatch(new AgentEvent(null, ctx.getTaskId(), TaskEventType.TASK_FAILED,
                ctx.getCurrentStepId(), Map.of(), Instant.now()), ctx);
    }

    private void dispatch(AgentEvent event, AgentExecutionContext ctx) {
        if (consolidationSubscriber == null) return;
        try {
            consolidationExecutor.execute(() -> {
                Runnable consolidation = () -> {
                    try {
                        consolidationSubscriber.onEvent(event, ctx);
                    } catch (RuntimeException e) {
                        log.warn("[MEMORY] consolidation failed taskId={}: {}",
                                ctx.getTaskId(), e.getMessage());
                    }
                };
                ExecutionContext executionContext = ctx.getAgentContext() != null
                        ? ctx.getAgentContext().getExecCtx() : null;
                if (executionContext == null) consolidation.run();
                else ExecutionContextHolder.runWith(executionContext, consolidation);
            });
        } catch (RuntimeException e) {
            log.warn("[MEMORY] consolidation dispatch rejected taskId={}: {}", ctx.getTaskId(), e.getMessage());
        }
    }

    private String finalOutput(AgentExecutionContext ctx) {
        if (ctx.getPlan() == null || ctx.getPlan().steps().isEmpty()) return "completed";
        return ctx.getPlan().steps().stream()
                .filter(step -> step.output() != null && !step.output().isBlank())
                .reduce((first, second) -> second)
                .map(step -> step.output())
                .orElse("completed");
    }
}
