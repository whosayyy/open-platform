package com.yin.ai.platform.core.agent;

import java.time.Instant;
import java.util.Map;

/** Transport-neutral runtime event. SSE and WebSocket mapping belongs to adapters. */
public record AgentEvent(
        String id,
        String taskId,
        String type,
        String stepId,
        Map<String, Object> payload,
        Instant timestamp
) {
    public AgentEvent {
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("taskId must not be blank");
        }
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("type must not be blank");
        }
        payload = payload == null ? Map.of() : Map.copyOf(payload);
        timestamp = timestamp == null ? Instant.now() : timestamp;
    }
}
