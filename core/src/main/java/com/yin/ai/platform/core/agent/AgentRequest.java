package com.yin.ai.platform.core.agent;

import com.yin.ai.platform.core.context.ExecutionContext;

import java.util.Map;

/** Framework-neutral request accepted by the canonical agent runtime. */
public record AgentRequest(
        String goal,
        String userId,
        String sessionId,
        Map<String, String> metadata,
        String systemPrompt,
        Double temperature,
        String model,
        ExecutionContext executionContext
) {
    public AgentRequest {
        if (goal == null || goal.isBlank()) {
            throw new IllegalArgumentException("goal must not be blank");
        }
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public static AgentRequest of(String goal, String userId, String sessionId,
                                  ExecutionContext executionContext) {
        return new AgentRequest(goal, userId, sessionId, Map.of(), null, null, null,
                executionContext);
    }
}
