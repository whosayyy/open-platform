package com.yin.ai.platform.memory.policy;

import com.yin.ai.platform.core.memory.MemoryType;

/**
 * 淘汰策略 — 控制当 Memory 超过水位线时的淘汰行为。
 * <p>
 * 淘汰流程：
 * <ol>
 *   <li>检查 Episodic Cell 总数是否超过 highWatermark</li>
 *   <li>若超过，按 strategy 排序选择淘汰候选</li>
 *   <li>淘汰至 lowWatermark 停止</li>
 * </ol>
 *
 * @param strategy      淘汰策略（LRU/LFU/FIFO/PRIORITY）
 * @param highWatermark 开始淘汰的 Cell 数量上限
 * @param lowWatermark  停止淘汰的 Cell 数量下限
 * @param memoryType    适用的 Memory 类型
 * @param enabled        是否启用
 */
public record EvictionPolicy(
        EvictionStrategy strategy,
        int highWatermark,
        int lowWatermark,
        MemoryType memoryType,
        boolean enabled) {

    public EvictionPolicy {
        strategy = strategy != null ? strategy : EvictionStrategy.FIFO;
        memoryType = memoryType != null ? memoryType : MemoryType.EPISODIC;
        if (enabled && (lowWatermark < 0 || highWatermark <= lowWatermark)) {
            throw new IllegalArgumentException(
                    "enabled eviction requires highWatermark > lowWatermark >= 0");
        }
    }

    public static EvictionPolicy defaults() {
        return new EvictionPolicy(EvictionStrategy.FIFO, 10000, 8000,
                MemoryType.EPISODIC, true);
    }

    public static EvictionPolicy disabled() {
        return new EvictionPolicy(EvictionStrategy.FIFO, 0, 0,
                MemoryType.EPISODIC, false);
    }
}
