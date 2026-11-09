package com.yin.ai.platform.core.runtime.execution;

import java.time.Instant;

/** Framework-neutral event emitted by an execution engine. */
public record ExecutionEvent<T>(
        EventType type,
        T data,
        long seq,
        Instant timestamp
) {
    public enum EventType {
        CHUNK,
        TOOL_CALL_START,
        TOOL_CALL_RESULT,
        COMPLETE,
        ERROR,
        CANCELLED
    }
}
