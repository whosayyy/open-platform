package com.yin.ai.platform.core.runtime.step;

/** Framework-neutral result returned by a plan step executor. */
public record StepExecutorResult(
        boolean success,
        String output,
        String error,
        long tokensUsed,
        long durationMs
) {
    public static StepExecutorResult ok(String output, long tokensUsed, long durationMs) {
        return new StepExecutorResult(true, output, null, tokensUsed, durationMs);
    }

    public static StepExecutorResult fail(String error, long durationMs) {
        return new StepExecutorResult(false, null, error, 0, durationMs);
    }
}
