package com.yin.ai.platform.agent.task;

/** Per-task token guardrails. */
public record TaskTokenBudget(
        long maxTotalTokens,
        long maxTokensPerStep,
        int maxLlmCallsPerStep,
        int warningThresholdPercent
) {
    public static final TaskTokenBudget DEFAULT = new TaskTokenBudget(200_000, 30_000, 10, 80);

    public TaskTokenBudget {
        if (maxTotalTokens <= 0 || maxTokensPerStep <= 0 || maxLlmCallsPerStep <= 0) {
            throw new IllegalArgumentException("token budgets must be positive");
        }
        if (warningThresholdPercent < 1 || warningThresholdPercent > 100) {
            throw new IllegalArgumentException("warningThresholdPercent must be between 1 and 100");
        }
    }
}
