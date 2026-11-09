package com.yin.ai.platform.agent.observer;

import com.yin.ai.platform.agent.AgentConfig;
import com.yin.ai.platform.agent.AgentContext;
import com.yin.ai.platform.agent.AgentTask;
import com.yin.ai.platform.agent.executor.StepResult;
import com.yin.ai.platform.engine.ExecutionContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("DefaultObserver")
class DefaultObserverTest {

    private AgentContext ctx;
    private DefaultObserver observer;

    @BeforeEach
    void setUp() {
        ExecutionContext execCtx = new ExecutionContext(
                UUID.randomUUID().toString(), null, null, null, null,
                Map.of(), null, null, Instant.now(), Map.of());
        AgentConfig config = AgentConfig.defaults();
        AgentTask task = new AgentTask("test", Map.of(), null, config);
        ctx = new AgentContext(execCtx, task, 0);
        observer = new DefaultObserver();
    }

    @Test
    @DisplayName("successful step with adequate output → SUCCESS")
    void successWithAdequateOutput() {
        StepResult result = StepResult.ok("The AI chip market is projected to grow 35% annually", 100L);

        Observation obs = observer.observe(result, ctx);

        assertThat(obs.status()).isEqualTo(Observation.ObservationStatus.SUCCESS);
        assertThat(obs.summary()).contains("AI chip market");
    }

    @Test
    @DisplayName("failed step → FAILURE")
    void failedStep() {
        StepResult result = StepResult.fail("connection timeout", 500L);

        Observation obs = observer.observe(result, ctx);

        assertThat(obs.status()).isEqualTo(Observation.ObservationStatus.FAILURE);
        assertThat(obs.summary()).contains("connection timeout");
    }

    @Test
    @DisplayName("null output → PARTIAL")
    void nullOutput() {
        StepResult result = StepResult.ok(null, 50L);

        Observation obs = observer.observe(result, ctx);

        assertThat(obs.status()).isEqualTo(Observation.ObservationStatus.PARTIAL);
        assertThat(obs.summary()).contains("empty");
    }

    @Test
    @DisplayName("blank output → PARTIAL")
    void blankOutput() {
        StepResult result = StepResult.ok("   ", 50L);

        Observation obs = observer.observe(result, ctx);

        assertThat(obs.status()).isEqualTo(Observation.ObservationStatus.PARTIAL);
    }

    @Test
    @DisplayName("short output (< 20 chars) → PARTIAL")
    void shortOutput() {
        StepResult result = StepResult.ok("OK", 10L);

        Observation obs = observer.observe(result, ctx);

        assertThat(obs.status()).isEqualTo(Observation.ObservationStatus.PARTIAL);
        assertThat(obs.summary()).contains("short output");
    }

    @Test
    @DisplayName("long output → truncated to 200 chars")
    void longOutputTruncated() {
        String longText = "A".repeat(300);
        StepResult result = StepResult.ok(longText, 100L);

        Observation obs = observer.observe(result, ctx);

        assertThat(obs.status()).isEqualTo(Observation.ObservationStatus.SUCCESS);
        assertThat(obs.summary()).endsWith("...");
        assertThat(obs.summary().length()).isLessThanOrEqualTo(203);
    }

    @Test
    @DisplayName("output 21 chars → SUCCESS with full text")
    void justAboveThreshold() {
        StepResult result = StepResult.ok("123456789012345678901", 10L);

        Observation obs = observer.observe(result, ctx);

        assertThat(obs.status()).isEqualTo(Observation.ObservationStatus.SUCCESS);
    }

    @Test
    @DisplayName("ellipsis handles null")
    void ellipsisNull() {
        assertThat(DefaultObserver.ellipsis(null, 10)).isEmpty();
    }
}
