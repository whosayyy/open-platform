package com.yin.ai.platform.agent.task;

import com.yin.ai.platform.agent.pattern.AgentMode;
import com.yin.ai.platform.agent.pattern.TaskPlan;

import java.time.Instant;
import java.util.Map;

/**
 * 任务快照 — 从 Redis 读取的任务完整状态，用于恢复和监控。
 *
 * @param taskId      任务 ID
 * @param goal        任务目标
 * @param userId      用户 ID
 * @param sessionId   会话 ID
 * @param mode        执行模式
 * @param state       当前状态
 * @param plan        执行计划（可为 null，SIMPLE/REACT 模式无计划）
 * @param stepIndex   当前执行到第几步
 * @param variables   上下文变量快照
 * @param createdAt   创建时间
 * @param updatedAt   最后更新时间
 * @param error       错误信息（FAILED 时）
 */
public record TaskSnapshot(
        String taskId,
        String goal,
        String userId,
        String sessionId,
        AgentMode mode,
        TaskState state,
        TaskPlan plan,
        int stepIndex,
        Map<String, Object> variables,
        Instant createdAt,
        Instant updatedAt,
        String error,
        String parentTaskId,
        String rootTaskId,
        String agentRole,
        String teamStatus
) {
    /** Backwards-compatible constructor used by existing tests and integrations. */
    public TaskSnapshot(String taskId, String goal, String userId, String sessionId,
                        AgentMode mode, TaskState state, TaskPlan plan, int stepIndex,
                        Map<String, Object> variables, Instant createdAt, Instant updatedAt,
                        String error) {
        this(taskId, goal, userId, sessionId, mode, state, plan, stepIndex, variables,
                createdAt, updatedAt, error, null, taskId, null, null);
    }
}
