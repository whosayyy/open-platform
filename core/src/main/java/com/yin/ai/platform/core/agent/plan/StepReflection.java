package com.yin.ai.platform.core.agent.plan;

import java.util.List;

/** Quality decision produced after a plan step. */
public record StepReflection(
        Decision decision,
        String reason,
        String retryHint,
        List<PlanStep> newSteps
) {
    public StepReflection {
        decision = decision == null ? Decision.CONTINUE : decision;
        newSteps = newSteps == null ? List.of() : List.copyOf(newSteps);
    }

    public static StepReflection continueExecution() {
        return new StepReflection(Decision.CONTINUE, null, null, List.of());
    }

    public enum Decision {
        CONTINUE,
        RETRY,
        REPLAN,
        SKIP_TO_SYNTHESIS,
        ABORT
    }
}
