package com.yin.ai.platform.agent.planner;

import com.yin.ai.platform.agent.AgentContext;

import java.util.Collections;

/**
 * NoOp 规划器 — D1 占位实现，返回单步计划直接完成。
 */
public class NoOpPlanner implements Planner {

    @Override
    public Plan plan(AgentContext ctx) {
        Step step = Step.of("noop", ctx.getTask().goal());
        return Plan.ofSteps(Collections.singletonList(step));
    }
}
