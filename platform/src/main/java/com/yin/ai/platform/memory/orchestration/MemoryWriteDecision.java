package com.yin.ai.platform.memory.orchestration;

import com.yin.ai.platform.core.memory.MemoryType;
import com.yin.ai.platform.core.memory.security.MemoryScope;

import java.time.Duration;

/** Deterministic write-policy output. */
public record MemoryWriteDecision(
        boolean shouldRemember, MemoryType type, MemoryScope scope, String scopeId,
        double importance, double confidence, Duration retention, String reason) {

    public MemoryWriteDecision {
        importance = Math.clamp(importance, 0.0, 1.0);
        confidence = Math.clamp(confidence, 0.0, 1.0);
        reason = reason != null ? reason : "";
    }

    public static MemoryWriteDecision reject(String reason) {
        return new MemoryWriteDecision(false, null, null, null,
                0, 1, Duration.ZERO, reason);
    }
}
