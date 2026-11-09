package com.yin.ai.platform.core.agent.plan;

import com.yin.ai.platform.core.agent.pattern.PatternContext;

/** Optional quality gate for iterative plan and research execution. */
@FunctionalInterface
public interface StepReflector {

    StepReflector ACCEPT_ALL = (step, plan, context) -> StepReflection.continueExecution();

    StepReflection reflect(PlanStep completedStep, AgentPlan plan, PatternContext context);
}
