package com.yin.ai.platform.core.agent;

import java.time.Instant;
import java.util.Map;

/** Read model for runtime monitoring without leaking persistence DTOs. */
public record AgentSnapshot(
        String taskId,
        String parentTaskId,
        String goal,
        AgentStatus status,
        String mode,
        Instant createdAt,
        Instant updatedAt,
        Map<String, Object> variables
) {
    public AgentSnapshot {
        variables = variables == null ? Map.of() : Map.copyOf(variables);
    }
}
