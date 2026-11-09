package com.yin.ai.platform.agent.observe;

import com.yin.ai.platform.agent.pattern.AgentExecutionContext;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/** Low-cardinality task, step and token metrics shared by every Agent pattern. */
@Component
public class AgentRuntimeMetrics {

    private final MeterRegistry registry;

    public AgentRuntimeMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void record(AgentExecutionContext context, String outcome) {
        String mode = context.getAgentMode() != null
                ? context.getAgentMode().name().toLowerCase() : "unknown";
        String safeOutcome = outcome != null ? outcome : "unknown";
        Tags tags = Tags.of("mode", mode, "outcome", safeOutcome);
        registry.counter("ai.agent.runtime.tasks", tags).increment();
        registry.timer("ai.agent.runtime.duration", tags)
                .record(Duration.between(context.getCreatedAt(), Instant.now()));
        int steps = context.getStepRecords() != null ? context.getStepRecords().size() : 0;
        registry.summary("ai.agent.runtime.steps", tags).record(steps);
        Object totalTokens = context.getVariable("usage.totalTokens");
        if (totalTokens instanceof Number number && number.longValue() > 0) {
            registry.summary("ai.agent.runtime.tokens", tags).record(number.longValue());
        }
    }
}
