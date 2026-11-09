package com.yin.ai.platform.core.memory;

/**
 * MemoryCell 唯一标识 — 原子记忆单元的身份凭证。
 * 全局唯一，用于 MemoryRelation 中的 from/to 引用。
 */
public record MemoryCellId(String id) {

    public static MemoryCellId of(String id) {
        return new MemoryCellId(id);
    }

    @Override
    public String toString() {
        return "Cell[" + id + "]";
    }
}
