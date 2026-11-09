package com.yin.ai.platform.core.memory;

import java.util.Map;

/**
 * Memory 关系 — Knowledge Graph 中的一条有向边。
 * <p>
 * 紧凑构造函数在 record 构造时自动校验 from/to/type 非空，
 * 以及 weight 在 [0, 1] 区间。
 * 提供 causal/temporal/reference 等语义化工厂方法。
 */
public record MemoryRelation(
        MemoryCellId from,
        MemoryCellId to,
        RelationType type,
        double weight,
        Map<String, String> attributes) {

    public MemoryRelation {
        if (from == null || to == null || type == null) {
            throw new IllegalArgumentException("from, to, and type must not be null");
        }
        if (weight < 0 || weight > 1) {
            throw new IllegalArgumentException("weight must be in [0, 1]");
        }
    }

    public static MemoryRelation of(MemoryCellId from, MemoryCellId to, RelationType type, double weight) {
        return new MemoryRelation(from, to, type, weight, Map.of());
    }

    /** 因果链：A 的执行结果导致了 B */
    public static MemoryRelation causal(MemoryCellId from, MemoryCellId to) {
        return new MemoryRelation(from, to, RelationType.CAUSAL, 1.0, Map.of());
    }

    /** 时间序：A 发生在 B 之前 */
    public static MemoryRelation temporal(MemoryCellId from, MemoryCellId to) {
        return new MemoryRelation(from, to, RelationType.TEMPORAL, 1.0, Map.of());
    }

    /** 引用关系：weight 表示引用强度 */
    public static MemoryRelation reference(MemoryCellId from, MemoryCellId to, double weight) {
        return new MemoryRelation(from, to, RelationType.REFERENCES, weight, Map.of());
    }
}
