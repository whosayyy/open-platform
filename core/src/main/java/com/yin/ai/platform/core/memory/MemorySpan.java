package com.yin.ai.platform.core.memory;

import java.time.Duration;
import java.time.Instant;

/**
 * Memory 时间范围 — 描述一个 Memory 在 Timeline 上的起止位置。
 * 用于时间范围查询、Episode 边界判定和 Checkpoint 定位。
 */
public record MemorySpan(Instant start, Instant end, long cellCount) {

    public static MemorySpan of(Instant start, Instant end) {
        return new MemorySpan(start, end, 0);
    }

    public static MemorySpan of(Instant start, Instant end, long cellCount) {
        return new MemorySpan(start, end, cellCount);
    }

    /** 计算时间跨度，null-safe */
    public Duration duration() {
        if (start == null || end == null) {
            return Duration.ZERO;
        }
        return Duration.between(start, end);
    }
}
