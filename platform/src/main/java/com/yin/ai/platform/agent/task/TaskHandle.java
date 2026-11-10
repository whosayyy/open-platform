package com.yin.ai.platform.agent.task;

import com.yin.ai.platform.core.agent.mode.AgentMode;

import java.time.Instant;

/**
 * 任务句柄 — TaskEngine.create() 的返回值，后续操作的凭证。
 *
 * @param taskId       任务唯一标识
 * @param mode         执行模式
 * @param initialState 初始状态（通常为 CREATED）
 * @param createdAt    创建时间
 */
public record TaskHandle(
        String taskId,
        AgentMode mode,
        TaskState initialState,
        Instant createdAt
) {}
