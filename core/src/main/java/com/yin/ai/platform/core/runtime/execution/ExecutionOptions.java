package com.yin.ai.platform.core.runtime.execution;

import java.time.Duration;

/** Immutable options supplied to framework-neutral execution policies. */
public record ExecutionOptions(
        String operationName,
        Duration timeout,
        boolean cancellable,
        RetryPolicy retryPolicy
) {
    public static ExecutionOptions of(String operationName, Duration timeout) {
        return new ExecutionOptions(operationName, timeout, true, null);
    }

    public static ExecutionOptions defaultOptions() {
        return new ExecutionOptions("unnamed", Duration.ofSeconds(30), true, null);
    }
}
