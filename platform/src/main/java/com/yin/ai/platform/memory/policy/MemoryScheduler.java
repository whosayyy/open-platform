package com.yin.ai.platform.memory.policy;

import com.yin.ai.platform.core.memory.MemoryId;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Future;

/**
 * Memory 调度器 SPI — 管理后台维护任务的调度。
 * <p>
 * 设计为 SPI，默认实现可基于 {@link java.util.concurrent.ScheduledExecutorService}。
 * 生产环境可替换为 Quartz / XXL-Job 等分布式调度框架。
 * <p>
 * 典型使用：
 * <pre>
 *   MemoryScheduler scheduler = ...;
 *   scheduler.schedule(memoryId, policy);  // 一次性注册所有策略
 *   // 或在 append 后按需调度
 *   List<PolicyAction> actions = policyEngine.evaluate(id, policy, stats);
 *   for (PolicyAction action : actions) {
 *       scheduler.execute(action);
 *   }
 * </pre>
 */
public interface MemoryScheduler {

    /**
     * 一次性注册 MemoryId 的所有策略调度。
     */
    void schedule(MemoryId id, MemoryPolicy policy);

    /** 调度 Consolidation 任务 */
    Future<?> scheduleConsolidation(MemoryId id, ConsolidationPolicy policy);

    /** 调度 Compression 任务 */
    Future<?> scheduleCompression(MemoryId id, CompressionPolicy policy);

    /** 调度 Eviction 任务 */
    Future<?> scheduleEviction(MemoryId id, EvictionPolicy policy);

    /** 调度定期 Checkpoint */
    Future<?> scheduleCheckpoint(MemoryId id, Duration interval);

    /** 执行单个策略操作 */
    void execute(PolicyAction action);

    /** 取消指定 MemoryId 的所有调度 */
    void cancelAll(MemoryId id);

    /** 关闭调度器 */
    void shutdown();
}
