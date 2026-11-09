package com.yin.ai.platform.core.memory.api;

import com.yin.ai.platform.core.memory.LifecycleState;
import com.yin.ai.platform.core.memory.MemoryId;
import com.yin.ai.platform.core.memory.MemorySpan;
import com.yin.ai.platform.core.memory.MemoryType;

import java.util.Map;

/**
 * Memory 快照 — 某时刻 Memory 状态的只读视图。
 * 用于调试、监控和 Actuator 端点输出。
 */
public record MemorySnapshot(
        MemoryId id,
        MemoryType type,
        LifecycleState state,
        long cellCount,
        long sizeBytes,
        MemorySpan span,
        Map<String, Object> summary) {

    public MemorySnapshot {
        summary = summary != null ? Map.copyOf(summary) : Map.of();
    }
}
