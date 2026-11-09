package com.yin.ai.platform.core.agent;

import java.util.Map;

/** Final result returned without exposing a transport or reactive type. */
public record AgentResult(
        String taskId,
        AgentStatus status,
        String output,
        String error,
        Map<String, Object> metadata
) {
    public AgentResult {
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("taskId must not be blank");
        }
        status = status == null ? AgentStatus.FAILED : status;
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public boolean successful() {
        return status == AgentStatus.COMPLETED;
    }
}
