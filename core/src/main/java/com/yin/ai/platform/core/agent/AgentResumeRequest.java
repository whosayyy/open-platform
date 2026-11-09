package com.yin.ai.platform.core.agent;

import com.yin.ai.platform.core.context.ExecutionContext;

import java.util.Map;

/** Resume request with explicit execution context and optional variable overrides. */
public record AgentResumeRequest(
        String taskId,
        Map<String, String> overrides,
        ExecutionContext executionContext
) {
    public AgentResumeRequest {
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("taskId must not be blank");
        }
        overrides = overrides == null ? Map.of() : Map.copyOf(overrides);
    }
}
