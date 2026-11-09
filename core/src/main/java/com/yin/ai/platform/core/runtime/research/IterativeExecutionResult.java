package com.yin.ai.platform.core.runtime.research;

/** Final counters and status produced by an iterative research execution. */
public record IterativeExecutionResult(
        boolean success,
        long completedSteps,
        int totalSteps,
        int roundsUsed,
        String error
) {
    public static IterativeExecutionResult success(long completed, int total, int rounds) {
        return new IterativeExecutionResult(true, completed, total, rounds, null);
    }

    public static IterativeExecutionResult failed(String error) {
        return new IterativeExecutionResult(false, 0, 0, 0, error);
    }
}
