package com.yin.ai.platform.core.runtime.execution;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;

/** Provider-neutral retry policy used by runtime execution ports. */
public record RetryPolicy(
        int maxRetries,
        Duration initialBackoff,
        double backoffMultiplier,
        Duration maxBackoff,
        Predicate<Throwable> retryableFilter
) {
    public static RetryPolicy standard() {
        return new RetryPolicy(
                2,
                Duration.ofSeconds(1),
                2.0,
                Duration.ofSeconds(4),
                ex -> ex instanceof IOException
                        || ex instanceof TimeoutException
                        || (ex instanceof RuntimeException
                        && ex.getMessage() != null
                        && (ex.getMessage().contains("429") || ex.getMessage().contains("503")))
        );
    }

    public Duration backoffForAttempt(int attempt) {
        long ms = (long) (initialBackoff.toMillis() * Math.pow(backoffMultiplier, attempt));
        return Duration.ofMillis(Math.min(ms, maxBackoff.toMillis()));
    }
}
