package com.yin.ai.platform.agent.pattern;

import java.time.Instant;

/** A single idempotent execution attempt for one plan step. */
public record StepExecution(
        String executionId,
        String stepId,
        int attempt,
        StepStatus status,
        Instant startedAt,
        Instant completedAt,
        long durationMs
) {
    public static StepExecution started(String taskId, String stepId, int attempt) {
        return new StepExecution(taskId + ":" + stepId + ":" + attempt, stepId, attempt,
                StepStatus.RUNNING, Instant.now(), null, 0);
    }

    public StepExecution complete(StepStatus terminalStatus) {
        Instant completed = Instant.now();
        return new StepExecution(executionId, stepId, attempt, terminalStatus, startedAt, completed,
                Math.max(0, java.time.Duration.between(startedAt, completed).toMillis()));
    }
}
