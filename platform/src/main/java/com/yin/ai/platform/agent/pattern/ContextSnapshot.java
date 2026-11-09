package com.yin.ai.platform.agent.pattern;

import com.yin.ai.platform.agent.task.TaskState;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 不可变上下文快照 — 用于 Redis 序列化和中断恢复。
 * <p>
 * 由 {@link AgentExecutionContext#snapshot()} 生成，
 * 由 {@code ContextStore.saveSnapshot()} 写入 Redis，
 * 由 {@code AgentExecutionContext.restore()} 从快照重建。
 */
public record ContextSnapshot(
        String taskId,
        String sessionId,
        String userId,
        AgentMode agentMode,
        TaskState state,
        String currentStepId,
        int stepIndex,
        Map<String, Object> variables,
        List<Artifact> artifacts,
        List<StepRecord> stepRecords,
        TaskPlan plan,
        Instant createdAt,
        Instant updatedAt
) {}
