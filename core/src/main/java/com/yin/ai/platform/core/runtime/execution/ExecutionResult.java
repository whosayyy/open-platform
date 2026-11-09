package com.yin.ai.platform.core.runtime.execution;

import java.time.Duration;

/** Transport-neutral result of a synchronous or asynchronous execution. */
public record ExecutionResult<T>(
        boolean success,
        T data,
        Throwable error,
        Duration duration,
        int retryCount,
        boolean cancelled
) {
    public static <T> ExecutionResult<T> ok(T data, Duration duration, int retries) {
        return new ExecutionResult<>(true, data, null, duration, retries, false);
    }

    public static <T> ExecutionResult<T> fail(Throwable error, Duration duration, int retries) {
        return new ExecutionResult<>(false, null, error, duration, retries, false);
    }

    public static <T> ExecutionResult<T> cancelled(Duration duration) {
        return new ExecutionResult<>(false, null, null, duration, 0, true);
    }
}
