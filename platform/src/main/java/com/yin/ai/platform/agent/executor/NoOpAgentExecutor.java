package com.yin.ai.platform.agent.executor;

import com.yin.ai.platform.agent.AgentContext;
import com.yin.ai.platform.agent.planner.Step;

/**
 * NoOp 执行器 — D1 占位实现，返回步骤描述作为输出。
 */
public class NoOpAgentExecutor implements AgentExecutor {

    @Override
    public StepResult execute(Step step, AgentContext ctx) {
        long start = System.currentTimeMillis();
        ctx.addMessage("assistant", "executed: " + step.description());
        return StepResult.ok("step completed: " + step.description(),
                System.currentTimeMillis() - start);
    }
}
