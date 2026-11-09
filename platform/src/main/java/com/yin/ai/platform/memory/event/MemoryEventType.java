package com.yin.ai.platform.memory.event;

/**
 * Memory 事件类型 — 覆盖 Memory 全生命周期的关键节点。
 * <p>
 * 每个事件类型对应一种状态转换或操作，由 {@link MemoryEventBus} 发布，
 * 内置监听器（Observability / Consolidation / Checkpoint）据此触发后续动作。
 */
public enum MemoryEventType {
    CREATED,
    LOADED,
    HYDRATED,
    APPENDED,
    APPEND_BATCH,
    CONSOLIDATED,
    COMPRESSED,
    ARCHIVED,
    DELETED,
    RECOVERING,
    RECOVERED,
    CHECKPOINT_CREATED,
    CHECKPOINT_DELETED,
    ERROR
}
