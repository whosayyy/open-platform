package com.yin.ai.platform.agent.event;

import java.time.Instant;
import java.util.Map;

/** Canonical client-facing event envelope. */
public record AgentEventEnvelope(
        String eventId,
        String eventType,
        String taskId,
        String sessionId,
        String traceId,
        String stepId,
        long sequence,
        Instant timestamp,
        Map<String, Object> payload
) {
    public static AgentEventEnvelope from(AgentEvent event, String sessionId, String traceId) {
        long sequence = -1;
        try { sequence = Long.parseLong(event.id()); } catch (Exception ignored) { }
        String eventType = event.type().name().toLowerCase(java.util.Locale.ROOT).replace('_', '.');
        return new AgentEventEnvelope(event.id(), eventType, event.taskId(), sessionId, traceId,
                event.stepId(), sequence, event.timestamp(),
                event.payload() == null ? Map.of() : event.payload());
    }
}
