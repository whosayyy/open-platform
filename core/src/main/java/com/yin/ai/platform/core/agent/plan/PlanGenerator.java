package com.yin.ai.platform.core.agent.plan;

import com.yin.ai.platform.core.agent.pattern.PatternContext;

/** Creates an executable plan from a goal and the available core ports. */
@FunctionalInterface
public interface PlanGenerator {

    AgentPlan generate(String goal, PatternContext context);
}
