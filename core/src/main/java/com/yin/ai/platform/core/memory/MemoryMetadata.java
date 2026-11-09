package com.yin.ai.platform.core.memory;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Memory 元数据 — 记录 Memory 的追踪、归属和统计信息。
 * <p>
 * traceId / conversationId / userId / agentId 用于关联 OpenTelemetry 链路追踪，
 * labels 提供灵活的自定义标签过滤，sizeBytes 用于存储容量管理。
 * 设计为不可变 record，所有 with* 方法返回新实例。
 */
public record MemoryMetadata(
        String traceId,
        String conversationId,
        Long userId,
        String agentId,
        Map<String, String> labels,
        Instant createdAt,
        Instant updatedAt,
        long sizeBytes) {

    public MemoryMetadata {
        labels = labels != null ? Map.copyOf(labels) : Map.of();
    }

    public static MemoryMetadata empty() {
        return new MemoryMetadata(null, null, null, null,
                Map.of(), Instant.now(), Instant.now(), 0);
    }

    public MemoryMetadata withUpdatedAt(Instant updatedAt) {
        return new MemoryMetadata(traceId, conversationId, userId, agentId,
                labels, createdAt, updatedAt, sizeBytes);
    }

    public MemoryMetadata withSizeBytes(long sizeBytes) {
        return new MemoryMetadata(traceId, conversationId, userId, agentId,
                labels, createdAt, Instant.now(), sizeBytes);
    }

    public MemoryMetadata withLabel(String key, String value) {
        Map<String, String> newLabels = new HashMap<>(labels);
        newLabels.put(key, value);
        return new MemoryMetadata(traceId, conversationId, userId, agentId,
                newLabels, createdAt, Instant.now(), sizeBytes);
    }
}
