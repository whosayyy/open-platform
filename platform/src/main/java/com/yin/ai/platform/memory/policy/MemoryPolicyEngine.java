package com.yin.ai.platform.memory.policy;

import com.yin.ai.platform.core.memory.api.MemoryStats;
import com.yin.ai.platform.core.memory.MemoryId;

import java.util.ArrayList;
import java.util.List;

/**
 * Memory 策略引擎 — 评估 Memory 当前状态，决定是否需要触发后台维护操作。
 * <p>
 * 每次 {@code append()} 后调用 {@link #evaluate(MemoryId, MemoryPolicy, MemoryStats)}，
 * 引擎根据四条策略分别判断：
 * <ol>
 *   <li>CompressionPolicy: Cell 数量超限或时间跨度过长 → COMPRESS</li>
 *   <li>ConsolidationPolicy: 时间间隔满足且 Cell 数足够 → CONSOLIDATE</li>
 *   <li>EvictionPolicy: Cell 数超过 highWatermark → EVICT</li>
 *   <li>Checkpoint: 重大操作后建议创建快照 → CHECKPOINT</li>
 * </ol>
 */
public class MemoryPolicyEngine {

    /**
     * 评估策略并返回建议操作列表。
     *
     * @param id     目标 MemoryId
     * @param policy 关联的策略配置
     * @param stats  当前 Memory 统计信息
     * @return 建议操作列表（可能为空）
     */
    public List<PolicyAction> evaluate(MemoryId id, MemoryPolicy policy, MemoryStats stats) {
        List<PolicyAction> actions = new ArrayList<>();

        // Compression: SIZE 或 TIME 触发
        if (policy.compression().enabled()) {
            CompressionPolicy cp = policy.compression();
            if (cp.trigger() == CompressionTrigger.SIZE && stats.totalCells() > cp.maxCells()) {
                actions.add(PolicyAction.compress(id,
                        "cell count " + stats.totalCells() + " exceeds " + cp.maxCells()));
            } else if (cp.trigger() == CompressionTrigger.TIME) {
                // TIME 触发由外部定时器控制，此处仅标记
                actions.add(PolicyAction.compress(id, "time-based trigger"));
            }
        }

        // Consolidation: Cell 数量达标
        if (policy.consolidation().enabled()
                && stats.totalCells() >= policy.consolidation().minCellsForConsolidation()) {
            actions.add(PolicyAction.consolidate(id,
                    "cell count " + stats.totalCells()
                            + " >= " + policy.consolidation().minCellsForConsolidation()));
        }

        // Eviction: 超过高水位线
        if (policy.eviction().enabled()
                && stats.totalCells() > policy.eviction().highWatermark()) {
            actions.add(PolicyAction.evict(id,
                    "cell count " + stats.totalCells()
                            + " > " + policy.eviction().highWatermark()));
        }

        return actions;
    }
}
