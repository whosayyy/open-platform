package com.yin.ai.platform.workflow.event;

import java.time.Instant;
import java.util.Map;

/**
 * 执行事件 — SSE 观察通道的事件载荷，同一任务保持统一 traceId。
 */
public record ExecutionEvent(
        String eventId,
        String taskRunId,
        String conversationId,
        String traceId,
        long sequence,
        ExecutionEventType type,
        Instant timestamp,
        Map<String, Object> payload
) {

    public static ExecutionEvent of(String taskRunId, String conversationId, String traceId,
                                    long sequence, ExecutionEventType type, Map<String, Object> payload) {
        return new ExecutionEvent(java.util.UUID.randomUUID().toString(), taskRunId, conversationId,
                traceId, sequence, type, Instant.now(), payload);
    }
}
