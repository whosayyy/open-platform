package com.yin.ai.platform.memory.temporal;

import com.yin.ai.platform.engine.ExecutionContext;
import com.yin.ai.platform.engine.ExecutionEngine;
import com.yin.ai.platform.core.runtime.execution.ExecutionOptions;
import com.yin.ai.platform.core.runtime.execution.ExecutionResult;
import com.yin.ai.platform.core.runtime.execution.RetryPolicy;
import com.yin.ai.platform.core.memory.LifecycleState;
import com.yin.ai.platform.core.memory.MemoryId;
import com.yin.ai.platform.memory.event.MemoryEventBus;
import com.yin.ai.platform.memory.event.MemoryEventType;
import com.yin.ai.platform.memory.lifecycle.MemoryLifecycle;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 故障恢复编排器 — 从最近 Checkpoint + Timeline 回放恢复 Memory。
 * <p>
 * 设计理由：
 * <ul>
 *   <li>委托 {@link ExecutionEngine#executeWithRetry} 执行回放，复用已有重试策略和线程池</li>
 *   <li>恢复流程：加载最近 Checkpoint → 创建 ReplaySession → 回放到 Timeline 末尾 → reconcile 状态机</li>
 *   <li>每个 MemoryId 同时只允许一个恢复任务（ConcurrentHashMap putIfAbsent 去重）</li>
 * </ul>
 */
public class RecoveryCoordinator {

    private final ExecutionEngine engine;
    private final RetryPolicy retryPolicy;
    private final MemoryLifecycle lifecycle;
    private final MemoryEventBus eventBus;
    private final Map<MemoryId, CompletableFuture<ReplaySession>> activeRecoveries = new ConcurrentHashMap<>();

    public RecoveryCoordinator(ExecutionEngine engine, MemoryLifecycle lifecycle,
                                MemoryEventBus eventBus) {
        this.engine = engine;
        this.retryPolicy = RetryPolicy.standard();
        this.lifecycle = lifecycle;
        this.eventBus = eventBus;
    }

    public RecoveryCoordinator(ExecutionEngine engine, RetryPolicy retryPolicy,
                                MemoryLifecycle lifecycle, MemoryEventBus eventBus) {
        this.engine = engine;
        this.retryPolicy = retryPolicy;
        this.lifecycle = lifecycle;
        this.eventBus = eventBus;
    }

    /** 从指定 Checkpoint 和 Timeline 恢复 Memory。同一 MemoryId 重复调用返回已有 Future。 */
    public CompletableFuture<ReplaySession> recover(MemoryId memoryId, Checkpoint checkpoint,
                                                     Timeline timeline, ExecutionContext ctx) {
        CompletableFuture<ReplaySession> existing = activeRecoveries.get(memoryId);
        if (existing != null && !existing.isDone()) {
            return existing;
        }

        eventBus.publish(MemoryEventType.RECOVERING, memoryId, null, ctx.traceId(),
                Map.of("checkpointId", checkpoint.id()));

        ExecutionOptions options = new ExecutionOptions(
                "memory-recover-" + memoryId.id(), Duration.ofMinutes(5), true, retryPolicy);

        CompletableFuture<ReplaySession> recovery = CompletableFuture.supplyAsync(() -> {
            ExecutionResult<ReplaySession> result = engine.executeWithRetry(ctx, () -> {
                lifecycle.transition(memoryId, LifecycleState.RECOVERING);

                ReplaySession session = new ReplaySession(memoryId, checkpoint, timeline);
                session.replayToEnd();

                if (session.isFailed()) {
                    throw new RuntimeException("Replay failed for " + memoryId);
                }

                lifecycle.transition(memoryId, LifecycleState.ACTIVE);
                eventBus.publish(MemoryEventType.RECOVERED, memoryId, null, ctx.traceId(),
                        Map.of("checkpointId", checkpoint.id(),
                                "replayedEntries", session.replayedEntries().size()));
                return session;
            }, options, retryPolicy);

            activeRecoveries.remove(memoryId);
            if (!result.success()) {
                throw new RuntimeException("Recovery failed for " + memoryId, result.error());
            }
            return result.data();
        });

        activeRecoveries.put(memoryId, recovery);
        return recovery;
    }

    /** 通过 Checkpoint 创建恢复快照。 */
    public Checkpoint createRecoveryCheckpoint(MemoryId memoryId, Timeline timeline,
                                                Map<String, byte[]> state) {
        TimelinePosition position = timeline.position();
        Checkpoint checkpoint = Checkpoint.full(memoryId, position, state);
        eventBus.publish(MemoryEventType.CHECKPOINT_CREATED, memoryId, null, null,
                Map.of("checkpointId", checkpoint.id(), "position", position));
        return checkpoint;
    }

    public int activeRecoveryCount() {
        return activeRecoveries.size();
    }
}
