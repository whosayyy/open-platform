package com.yin.ai.platform.agent.task;

import java.time.Instant;

/**
 * 步骤执行结果 — TaskEngine.saveStepResult() 的参数。
 * <p>
 * 注意：与 {@code com.yin.ai.platform.agent.executor.StepResult} 区分，
 * 后者是 ReAct Loop 内部单步结果，本类是平台级任务步骤结果。
 *
 * @param stepId    步骤 ID
 * @param success   是否成功
 * @param output    输出内容（成功时）
 * @param error     错误信息（失败时）
 * @param tokensUsed 本步骤消耗的 token 数
 * @param durationMs 执行耗时（毫秒）
 * @param completedAt 完成时间
 */
public record TaskStepResult(
        String stepId,
        boolean success,
        String output,
        String error,
        long tokensUsed,
        long durationMs,
        Instant completedAt
) {
    public static TaskStepResult success(String stepId, String output, long tokensUsed, long durationMs) {
        return new TaskStepResult(stepId, true, output, null, tokensUsed, durationMs, Instant.now());
    }

    public static TaskStepResult failure(String stepId, String error, long durationMs) {
        return new TaskStepResult(stepId, false, null, error, 0, durationMs, Instant.now());
    }
}
