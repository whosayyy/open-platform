package com.yin.ai.platform.agent.task;

import com.yin.ai.platform.agent.pattern.StepStatus;

import java.time.Instant;

/** Durable attempt record used to make recovery decisions explicit. */
public record StepExecutionRecord(
        String stepId,
        int attemptCount,
        Instant lastAttemptAt,
        StepStatus status,
        String output,
        String error,
        boolean sideEffectCommitted
) {
    public StepExecutionRecord nextAttempt() {
        return new StepExecutionRecord(stepId, attemptCount + 1, Instant.now(), StepStatus.RUNNING,
                output, null, sideEffectCommitted);
    }

    public StepExecutionRecord complete(String value) {
        return new StepExecutionRecord(stepId, attemptCount, Instant.now(), StepStatus.COMPLETED,
                value, null, sideEffectCommitted);
    }

    public StepExecutionRecord fail(String message) {
        return new StepExecutionRecord(stepId, attemptCount, Instant.now(), StepStatus.FAILED,
                output, message, sideEffectCommitted);
    }
}
