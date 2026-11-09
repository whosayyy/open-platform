package com.yin.ai.platform.agent.observer;

import com.yin.ai.platform.agent.AgentContext;
import com.yin.ai.platform.agent.executor.StepResult;

/**
 * NoOp 观察器 — D1 占位实现，始终返回 SUCCESS。
 */
public class NoOpObserver implements Observer {

    @Override
    public Observation observe(StepResult result, AgentContext ctx) {
        if (result.success()) {
            return Observation.success(result.output());
        }
        return Observation.failure(result.error());
    }
}
