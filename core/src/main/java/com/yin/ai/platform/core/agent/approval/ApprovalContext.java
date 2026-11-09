package com.yin.ai.platform.core.agent.approval;

import com.yin.ai.platform.core.agent.plan.AgentPlan;
import com.yin.ai.platform.core.agent.plan.PlanStep;
import com.yin.ai.platform.core.context.ExecutionContext;

import java.util.Objects;

/** Immutable core state exposed to a human-approval policy before a plan step. */
public record ApprovalContext(
        String taskId,
        String mode,
        AgentPlan plan,
        PlanStep step,
        int stepIndex,
        ExecutionContext executionContext
) {
    public ApprovalContext {
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("taskId must not be blank");
        }
        if (mode == null || mode.isBlank()) {
            throw new IllegalArgumentException("mode must not be blank");
        }
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(step, "step");
        Objects.requireNonNull(executionContext, "executionContext");
        stepIndex = Math.max(0, stepIndex);
    }
}
