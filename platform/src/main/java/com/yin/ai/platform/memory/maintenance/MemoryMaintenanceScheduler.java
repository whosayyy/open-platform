package com.yin.ai.platform.memory.maintenance;

import com.yin.ai.platform.core.memory.api.MemoryRuntime;
import com.yin.ai.platform.core.memory.api.MemoryStats;
import com.yin.ai.platform.memory.consolidation.MemoryConsolidator;
import com.yin.ai.platform.core.memory.MemoryCell;
import com.yin.ai.platform.core.memory.MemoryId;
import com.yin.ai.platform.core.memory.MemoryType;
import com.yin.ai.platform.memory.policy.MemoryPolicy;
import com.yin.ai.platform.memory.policy.MemoryPolicyEngine;
import com.yin.ai.platform.memory.policy.PolicyAction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 记忆生命周期维护调度器 — 定时触发压缩、整合、淘汰。
 * <p>
 * 编排现有组件：MemoryPolicyEngine → PolicyAction → MemoryConsolidator / MemoryRuntime。
 * 所有维护操作异步执行，不阻塞请求处理。
 * <p>
 * 【P1-1 修复】补充 @Component 使三个 @Scheduled 定时任务真正被 Spring 调度；
 * scheduledConsolidation 真正调用 consolidate()（原先只打日志不执行）。
 */
@Component
public class MemoryMaintenanceScheduler {

    private static final Logger log = LoggerFactory.getLogger(MemoryMaintenanceScheduler.class);

    private final MemoryRuntime memoryRuntime;
    private final MemoryPolicyEngine policyEngine;
    private final List<MemoryConsolidator> consolidators;
    private final MemoryMaintenanceConfig config;

    public MemoryMaintenanceScheduler(MemoryRuntime memoryRuntime,
                                       MemoryPolicyEngine policyEngine,
                                       List<MemoryConsolidator> consolidators,
                                       MemoryMaintenanceConfig config) {
        this.memoryRuntime = memoryRuntime;
        this.policyEngine = policyEngine;
        this.consolidators = consolidators != null ? consolidators : List.of();
        this.config = config;
    }

    /**
     * 定期压缩：检查 CELL_COUNT 超限，触发压缩建议。
     */
    @Scheduled(fixedDelayString = "${ai.platform.memory.maintenance.compaction-interval:300000}")
    public void scheduledCompaction() {
        if (!config.scheduledEnabled()) {
            return;
        }
        try {
            MemoryStats stats = memoryRuntime.stats();
            MemoryPolicy policy = MemoryPolicy.defaults();

            var actions = policyEngine.evaluate(null, policy, stats);
            List<PolicyAction> compactionActions = actions.stream()
                    .filter(a -> a.actionType() == PolicyAction.ActionType.COMPRESS)
                    .toList();

            if (!compactionActions.isEmpty()) {
                log.info("[MEM-MAINT] compaction triggered: {} cells, actions: {}",
                        stats.totalCells(),
                        compactionActions.stream().map(PolicyAction::reason).toList());
            } else {
                log.debug("[MEM-MAINT] compaction check: {} cells, no action needed", stats.totalCells());
            }
        } catch (Exception e) {
            log.error("[MEM-MAINT] compaction check failed: {}", e.getMessage(), e);
        }
    }

    /**
     * 定期整合：Episodic → Semantic 提取事实和模式。
     * <p>
     * P1-1 修复：遍历 registry 中的 EPISODIC/SESSION 记忆，将 timeline cells
     * 交给每个 Consolidator 真正执行 consolidate()（原实现只 log 不调用）。
     */
    @Scheduled(fixedDelayString = "${ai.platform.memory.maintenance.consolidation-interval:3600000}")
    public void scheduledConsolidation() {
        if (!config.scheduledEnabled() || consolidators.isEmpty()) {
            return;
        }
        try {
            MemoryStats stats = memoryRuntime.stats();
            log.info("[MEM-MAINT] consolidation check: {} total cells, {} consolidators available",
                    stats.totalCells(), consolidators.size());

            for (MemoryId memId : memoryRuntime.allMemoryIds()) {
                if (memId.type() != MemoryType.EPISODIC && memId.type() != MemoryType.SESSION) {
                    continue;
                }
                List<MemoryCell> cells = memoryRuntime.readTimeline(memId, 200);
                if (cells.isEmpty()) {
                    continue;
                }
                for (MemoryConsolidator consolidator : consolidators) {
                    try {
                        var result = consolidator.consolidate(memId, cells);
                        if (result != null && result.totalExtracted() > 0) {
                            // 【BUG 修复】consolidate 提取出的 Facts 原实现只打日志不落库（提取完就丢）。
                            // 写回策略：把 Facts 转成 SEMANTIC 类型 MemoryCell，走 memoryRuntime.appendBatch
                            // （复用 P0-2 修复后的链路：registry 注册 + ES 写透 + 审计落库）。
                            int written = writeBackFacts(memId, result, ctxFor(memId));
                            log.info("[MEM-MAINT] consolidator '{}' id={} extracted={} written={}",
                                    consolidator.name(), memId, result.totalExtracted(), written);
                        }
                    } catch (Exception e) {
                        log.warn("[MEM-MAINT] consolidator '{}' failed: {}", consolidator.name(), e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            log.error("[MEM-MAINT] consolidation check failed: {}", e.getMessage(), e);
        }
    }

    /**
     * 【BUG 修复】将 consolidation 提取的 Facts 写回 SEMANTIC 记忆。
     * <p>
     * 原实现只 log totalExtracted，提取的事实从未落库——相当于每次整合白跑。
     * 写回后事实变成 SEMANTIC 类型 MemoryCell：
     * <ul>
     *   <li>content = "subject predicate object"（三元组可读文本）</li>
     *   <li>attributes 带 confidence / sourceCellIds / extractor 追溯</li>
     *   <li>通过 memoryRuntime.appendBatch 写入（注册 registry + ES 写透 + 审计落库）</li>
     * </ul>
     *
     * @param sourceMemId 来源记忆（EPISODIC/SESSION）
     * @param result      consolidate 结果
     * @param ctx         记忆上下文（用户隔离）
     * @return 实际写入的 Fact 数
     */
    private int writeBackFacts(MemoryId sourceMemId,
                               com.yin.ai.platform.memory.consolidation.ConsolidationResult result,
                               com.yin.ai.platform.core.memory.MemoryContext ctx) {
        if (result == null || result.facts() == null || result.facts().isEmpty()) {
            return 0;
        }
        try {
            // 来源记忆的 ownerKey 保留，目标记忆 id 用语义标记；EPISODIC/SESSION → semantic:xx
            String semanticId = "semantic:" + sourceMemId.id();
            MemoryId target = MemoryId.of(semanticId, MemoryType.SEMANTIC, sourceMemId.ownerKey());

            List<MemoryCell> cells = new java.util.ArrayList<>(result.facts().size());
            for (com.yin.ai.platform.core.memory.semantic.Fact fact : result.facts()) {
                String content = fact.toTriple();
                if (content == null || content.isBlank()) {
                    continue;
                }
                java.util.Map<String, Object> attrs = new java.util.HashMap<>();
                attrs.put("confidence", Math.round(fact.confidence() * 100.0) / 100.0);
                attrs.put("sourceCellIds", fact.sourceCellIds());
                attrs.put("extractor", "consolidation");
                attrs.put("sourceMemoryId", sourceMemId.id());
                cells.add(MemoryCell.of(
                        com.yin.ai.platform.core.memory.MemoryCellId.of(
                                java.util.UUID.randomUUID().toString()),
                        MemoryType.SEMANTIC, content, attrs));
            }
            if (cells.isEmpty()) {
                return 0;
            }
            List<MemoryCell> stored = memoryRuntime.appendBatch(target, cells, ctx);
            log.info("[MEM-MAINT] written {} semantic facts -> {} (from {})",
                    stored.size(), target, sourceMemId);
            return stored.size();
        } catch (Exception e) {
            log.warn("[MEM-MAINT] writeBackFacts failed for {}: {}", sourceMemId, e.getMessage());
            return 0;
        }
    }

    /** 构建与来源记忆同用户的 MemoryContext（用于写回时保留租户隔离）。 */
    private com.yin.ai.platform.core.memory.MemoryContext ctxFor(MemoryId memId) {
        java.util.Map<String, Object> attrs = new java.util.HashMap<>();
        if (memId.ownerKey() != null && !memId.ownerKey().isBlank()) {
            attrs.put("ownerKey", memId.ownerKey());
        }
        return new com.yin.ai.platform.core.memory.MemoryContext(
                null, null, null, "memory-maintenance", "consolidation",
                java.time.Instant.now(), attrs);
    }

    /**
     * 定期淘汰：超过高水位线时清理过期 Cell。
     */
    @Scheduled(fixedDelayString = "${ai.platform.memory.maintenance.eviction-interval:1800000}")
    public void scheduledEviction() {
        if (!config.scheduledEnabled()) {
            return;
        }
        try {
            MemoryStats stats = memoryRuntime.stats();
            if (stats.totalCells() > config.evictionHighWatermark()) {
                log.warn("[MEM-MAINT] eviction needed: {} cells > {} highWatermark",
                        stats.totalCells(), config.evictionHighWatermark());
            } else {
                log.debug("[MEM-MAINT] eviction check: {} cells, below watermark {}",
                        stats.totalCells(), config.evictionHighWatermark());
            }
        } catch (Exception e) {
            log.error("[MEM-MAINT] eviction check failed: {}", e.getMessage(), e);
        }
    }
}
