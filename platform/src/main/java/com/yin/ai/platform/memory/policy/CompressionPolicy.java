package com.yin.ai.platform.memory.policy;

import com.yin.ai.platform.memory.compression.CompressionStrategy;

import java.time.Duration;

/**
 * 压缩策略 — 控制何时以及如何压缩 Memory。
 * <p>
 * 触发条件（满足任一即触发）：
 * <ul>
 *   <li>SIZE 触发：Cell 数量超过 {@code maxCells}</li>
 *   <li>TIME 触发：最早 Cell 的时间跨度超过 {@code maxAge}</li>
 *   <li>MANUAL：仅手动触发</li>
 * </ul>
 *
 * @param trigger   触发条件
 * @param maxCells  Cell 数量阈值（SIZE 触发时使用）
 * @param maxAge    时间跨度阈值（TIME 触发时使用）
 * @param strategy  压缩策略（SUMMARIZE / TRUNCATE / EXTRACT）
 * @param enabled   是否启用
 */
public record CompressionPolicy(
        CompressionTrigger trigger,
        int maxCells,
        Duration maxAge,
        CompressionStrategy strategy,
        boolean enabled) {

    public CompressionPolicy {
        maxCells = maxCells > 0 ? maxCells : 500;
        maxAge = maxAge != null ? maxAge : Duration.ofHours(24);
        strategy = strategy != null ? strategy : CompressionStrategy.TRUNCATE;
    }

    public static CompressionPolicy sizeTriggered(int maxCells) {
        return new CompressionPolicy(CompressionTrigger.SIZE, maxCells,
                null, CompressionStrategy.TRUNCATE, true);
    }

    public static CompressionPolicy timeTriggered(Duration maxAge) {
        return new CompressionPolicy(CompressionTrigger.TIME, 0,
                maxAge, CompressionStrategy.TRUNCATE, true);
    }

    public static CompressionPolicy disabled() {
        return new CompressionPolicy(CompressionTrigger.MANUAL, 0, null,
                CompressionStrategy.TRUNCATE, false);
    }
}
