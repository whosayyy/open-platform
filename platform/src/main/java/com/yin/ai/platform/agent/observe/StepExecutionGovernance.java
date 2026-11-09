package com.yin.ai.platform.agent.observe;

import com.yin.ai.platform.agent.pattern.AgentExecutionContext;
import com.yin.ai.platform.agent.memory.AgentMemoryLifecycle;
import com.yin.ai.platform.core.runtime.step.StepExecutorResult;
import com.yin.ai.platform.agent.task.StepExecutionRecord;
import com.yin.ai.platform.agent.task.TaskEngine;
import com.yin.ai.platform.agent.task.TaskTokenAccountant;

import java.time.Duration;
import java.time.Instant;

/** Shared budget, attempt, metrics and tracing layer for every pattern step executor. */
public class StepExecutionGovernance {

    private final TaskTokenAccountant tokenAccountant;
    private final TaskEngine taskEngine;
    private final StepMetrics metrics;
    private final StepTracer tracer;
    private final AgentMemoryLifecycle memoryLifecycle;

    public StepExecutionGovernance(TaskTokenAccountant tokenAccountant,
                                   TaskEngine taskEngine,
                                   StepMetrics metrics,
                                   StepTracer tracer,
                                   AgentMemoryLifecycle memoryLifecycle) {
        this.tokenAccountant = tokenAccountant;
        this.taskEngine = taskEngine;
        this.metrics = metrics;
        this.tracer = tracer;
        this.memoryLifecycle = memoryLifecycle;
    }

    public ExecutionScope before(String executorId, AgentExecutionContext ctx) {
        String taskId = ctx.getTaskId();
        String stepId = ctx.getCurrentStepId();
        if (tokenAccountant != null && !tokenAccountant.canExecuteStep(taskId, stepId)) {
            return ExecutionScope.rejected("TOKEN_BUDGET_EXCEEDED");
        }
        StepExecutionRecord previous = taskEngine != null ? taskEngine.getStepExecutionRecord(taskId, stepId) : null;
        StepExecutionRecord running = previous != null
                ? previous.nextAttempt()
                : new StepExecutionRecord(stepId, 1, Instant.now(),
                com.yin.ai.platform.agent.pattern.StepStatus.RUNNING, null, null, false);
        if (taskEngine != null) taskEngine.saveStepExecutionRecord(taskId, running);
        StepTracer.StepSpan span = tracer != null
                ? tracer.start(taskId, stepId, executorId, ctx.getAgentMode().name()) : null;
        return new ExecutionScope(running, span, System.nanoTime(), null);
    }

    public void after(String executorId, AgentExecutionContext ctx, ExecutionScope scope,
                      StepExecutorResult result) {
        if (scope.rejectionReason() != null) return;
        long tokens = Math.max(0, result.tokensUsed());
        String model = ctx.getVariable("model", String.class);
        if (tokenAccountant != null && tokens > 0) {
            tokenAccountant.record(ctx.getTaskId(), ctx.getCurrentStepId(), model, tokens);
        }
        if (taskEngine != null && scope.record() != null) {
            taskEngine.saveStepExecutionRecord(ctx.getTaskId(), result.success()
                    ? scope.record().complete(result.output()) : scope.record().fail(result.error()));
        }
        if (memoryLifecycle != null && result.success()) {
            memoryLifecycle.recordStep(ctx, result.output());
        }
        if (metrics != null) {
            metrics.record(ctx.getAgentMode().name(), executorId, result.success(),
                    Duration.ofNanos(System.nanoTime() - scope.startedNanos()), tokens,
                    scope.record() != null ? scope.record().attemptCount() : 1);
        }
        if (scope.span() != null) {
            scope.span().finish(result.success(), tokens, result.error());
            scope.span().close();
        }
    }

    public record ExecutionScope(StepExecutionRecord record, StepTracer.StepSpan span,
                                 long startedNanos, String rejectionReason) {
        static ExecutionScope rejected(String reason) {
            return new ExecutionScope(null, null, System.nanoTime(), reason);
        }
    }
}
