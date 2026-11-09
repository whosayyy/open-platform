package com.yin.ai.platform.memory.policy;

import com.yin.ai.platform.core.memory.MemoryId;

/**
 * 策略引擎建议的操作 — 由 {@link MemoryPolicyEngine} 评估后返回。
 *
 * @param actionType 操作类型
 * @param memoryId   目标 Memory
 * @param reason     触发原因（人类可读）
 */
public record PolicyAction(ActionType actionType, MemoryId memoryId, String reason) {

    public enum ActionType {
        COMPRESS,
        CONSOLIDATE,
        EVICT,
        CHECKPOINT
    }

    public static PolicyAction compress(MemoryId id, String reason) {
        return new PolicyAction(ActionType.COMPRESS, id, reason);
    }

    public static PolicyAction consolidate(MemoryId id, String reason) {
        return new PolicyAction(ActionType.CONSOLIDATE, id, reason);
    }

    public static PolicyAction evict(MemoryId id, String reason) {
        return new PolicyAction(ActionType.EVICT, id, reason);
    }

    public static PolicyAction checkpoint(MemoryId id, String reason) {
        return new PolicyAction(ActionType.CHECKPOINT, id, reason);
    }
}
