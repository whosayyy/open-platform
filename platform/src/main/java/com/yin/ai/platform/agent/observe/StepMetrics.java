package com.yin.ai.platform.agent.observe;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;

import java.time.Duration;
import java.util.List;

/** Low-cardinality per-step agent metrics. */
public class StepMetrics {

    private final MeterRegistry registry;

    public StepMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void record(String mode, String executor, boolean success, Duration duration,
                       long tokens, int attempt) {
        String effectiveMode = mode == null ? "unknown" : mode;
        String effectiveExecutor = executor == null ? "unknown" : executor;
        List<Tag> tags = List.of(Tag.of("mode", effectiveMode), Tag.of("executor", effectiveExecutor),
                Tag.of("status", success ? "success" : "failure"));
        registry.timer("ai.agent.step.duration", tags).record(duration);
        if (tokens > 0) {
            registry.summary("ai.agent.step.tokens", tags).record(tokens);
        }
        if (attempt > 1) {
            registry.counter("ai.agent.step.retries", "mode", effectiveMode,
                    "executor", effectiveExecutor).increment();
        }
    }
}
