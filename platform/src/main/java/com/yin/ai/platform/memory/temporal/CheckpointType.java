package com.yin.ai.platform.memory.temporal;

/**
 * Checkpoint 类型 — 控制快照粒度和恢复策略。
 * <p>
 * FULL: 序列化完整 Memory 状态，恢复时直接加载，恢复时间 O(1)，快照成本 O(N)。
 * INCREMENTAL: 仅记录自上次 Checkpoint 以来的增量，恢复时需叠加回放，快照成本 O(Δ)。
 */
public enum CheckpointType {
    FULL,
    INCREMENTAL
}
