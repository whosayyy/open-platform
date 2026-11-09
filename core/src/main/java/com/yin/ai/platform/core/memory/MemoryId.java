package com.yin.ai.platform.core.memory;

/**
 * Memory 唯一标识 — 由 id + type + ownerKey 三元组构成。
 * <p>
 * ownerKey 用于多租户/多用户隔离，type 用于路由到对应的 MemoryStore。
 * 设计为不可变 record，与 {@code ExecutionContext} 模式一致。
 */
public record MemoryId(String id, MemoryType type, String ownerKey) {

    public static MemoryId of(String id, MemoryType type, String ownerKey) {
        return new MemoryId(id, type, ownerKey);
    }

    @Override
    public String toString() {
        return "MemoryId[" + type + ":" + ownerKey + ":" + id + "]";
    }
}
