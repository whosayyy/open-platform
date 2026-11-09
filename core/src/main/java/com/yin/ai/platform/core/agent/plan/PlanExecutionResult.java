package com.yin.ai.platform.core.agent.plan;

import java.util.Map;

/** Final state and counters from plan execution. */
public record PlanExecutionResult(
        boolean success,
        boolean waiting,
        AgentPlan plan,
        String error,
        int rounds,
        Map<String, Object> metadata
) {
    public PlanExecutionResult(boolean success, AgentPlan plan, String error,
                               int rounds, Map<String, Object> metadata) {
        this(success, false, plan, error, rounds, metadata);
    }

    public PlanExecutionResult {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
