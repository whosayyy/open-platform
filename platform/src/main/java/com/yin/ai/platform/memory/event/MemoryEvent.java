package com.yin.ai.platform.memory.event;

import com.yin.ai.platform.core.memory.MemoryCell;
import com.yin.ai.platform.core.memory.MemoryId;
import com.yin.ai.platform.core.memory.MemoryType;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Memory 事件 — 不可变事件记录，代表 Memory 系统中发生的某件事。
 * <p>
 * 设计理由：事件是"已发生的事实"，不可变，不应携带回调或可变状态。
 * data 字段携带事件特有的元数据（如转换前后的状态、错误信息等）。
 */
public record MemoryEvent(
        String eventId,
        MemoryEventType type,
        MemoryId memoryId,
        MemoryType memoryType,
        MemoryCell cell,
        String traceId,
        long sequenceNumber,
        Instant timestamp,
        Map<String, Object> data) {

    public MemoryEvent {
        data = data != null ? Map.copyOf(data) : Map.of();
    }

    public static MemoryEvent of(MemoryEventType type, MemoryId memoryId, MemoryCell cell,
                                  String traceId, long sequenceNumber, Map<String, Object> data) {
        return new MemoryEvent(
                UUID.randomUUID().toString(), type, memoryId,
                memoryId != null ? memoryId.type() : null,
                cell, traceId, sequenceNumber, Instant.now(), data);
    }
}
