package com.yin.ai.platform.memory.policy;

import java.time.Duration;

/**
 * Consolidation 策略 — 控制 STM → LTM 转移的触发规则。
 * <p>
 * 触发条件：距离上次 Consolidation 超过 {@code interval} 且
 * Episodic Cell 数量超过 {@code minCellsForConsolidation}。
 *
 * @param interval                 Consolidation 间隔
 * @param scope                    Consolidation 范围
 * @param minCellsForConsolidation 触发的最小 Cell 数量
 * @param enabled                  是否启用
 */
public record ConsolidationPolicy(
        Duration interval,
        ConsolidationScope scope,
        int minCellsForConsolidation,
        boolean enabled) {

    public ConsolidationPolicy {
        interval = interval != null ? interval : Duration.ofHours(1);
        scope = scope != null ? scope : ConsolidationScope.ALL;
        minCellsForConsolidation = Math.max(minCellsForConsolidation, 10);
    }

    public static ConsolidationPolicy defaults() {
        return new ConsolidationPolicy(Duration.ofHours(1),
                ConsolidationScope.ALL, 50, true);
    }

    public static ConsolidationPolicy disabled() {
        return new ConsolidationPolicy(Duration.ofHours(1),
                ConsolidationScope.ALL, 0, false);
    }
}
