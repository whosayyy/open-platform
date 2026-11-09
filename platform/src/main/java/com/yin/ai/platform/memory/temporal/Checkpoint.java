package com.yin.ai.platform.memory.temporal;

import com.yin.ai.platform.core.memory.MemoryId;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Checkpoint — 时间点快照，用于故障恢复和断点续传。
 * <p>
 * 存储指定 MemoryId 在 Timeline 某个位置的状态序列化结果。
 * FULL 类型包含完整状态，INCREMENTAL 仅包含自上次 Checkpoint 以来的增量。
 * 恢复时：加载最近 FULL Checkpoint → 回放后续 INCREMENTAL Checkpoint → 回放剩余 Timeline 条目。
 *
 * @param id               唯一标识
 * @param memoryId         关联的 Memory
 * @param type             快照类型
 * @param timelinePosition 对应 Timeline 位置
 * @param state            序列化状态 Map（key=MemoryId, value=序列化字节）
 * @param createdAt        创建时间
 * @param metadata         扩展元数据
 */
public record Checkpoint(
        String id,
        MemoryId memoryId,
        CheckpointType type,
        TimelinePosition timelinePosition,
        Map<String, byte[]> state,
        Instant createdAt,
        Map<String, Object> metadata) {

    public Checkpoint {
        state = state != null ? Map.copyOf(state) : Map.of();
        metadata = metadata != null ? Map.copyOf(metadata) : Map.of();
    }

    public static Checkpoint full(MemoryId memoryId, TimelinePosition position,
                                   Map<String, byte[]> state) {
        return new Checkpoint(UUID.randomUUID().toString(), memoryId, CheckpointType.FULL,
                position, state, Instant.now(), Map.of());
    }

    public static Checkpoint incremental(MemoryId memoryId, TimelinePosition position,
                                          Map<String, byte[]> state, String baseCheckpointId) {
        return new Checkpoint(UUID.randomUUID().toString(), memoryId, CheckpointType.INCREMENTAL,
                position, state, Instant.now(),
                Map.of("baseCheckpointId", baseCheckpointId));
    }
}
