package com.yin.ai.platform.core.agent.plan;

import com.yin.ai.platform.core.agent.pattern.PatternContext;

/** Executes and mutates an immutable plan through returned snapshots. */
@FunctionalInterface
public interface PlanExecutor {

    PlanExecutionResult execute(AgentPlan plan, PatternContext context,
                                StepReflector reflector);
}
