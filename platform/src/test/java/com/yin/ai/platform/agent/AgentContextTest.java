package com.yin.ai.platform.agent;

import com.yin.ai.platform.engine.ExecutionContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("AgentContext")
class AgentContextTest {

    private ExecutionContext execCtx;

    @BeforeEach
    void setUp() {
        execCtx = new ExecutionContext(
                UUID.randomUUID().toString(), null, null, null, null,
                Map.of(), null, null, Instant.now(), Map.of());
    }

    @Test
    @DisplayName("create with task copies initial context variables")
    void createCopiesTaskContext() {
        AgentTask task = new AgentTask("test goal", Map.of("k1", "v1"), null, AgentConfig.defaults());
        AgentContext ctx = new AgentContext(execCtx, task, 0);

        assertThat(ctx.getAgentId()).isNotBlank();
        assertThat(ctx.getVariable("k1")).isEqualTo("v1");
        assertThat(ctx.getState()).isEqualTo(AgentState.IDLE);
        assertThat(ctx.getCurrentStep()).isEqualTo(0);
        assertThat(ctx.getDepth()).isEqualTo(0);
        assertThat(ctx.getConfig()).isEqualTo(AgentConfig.defaults());
    }

    @Test
    @DisplayName("create restores checkpoint messages into execution history")
    void createRestoresCheckpointMessages() {
        AgentTask task = new AgentTask("resume goal", Map.of(
                "resume", true,
                "resumeMessages", List.of("assistant:step one output", "assistant:step two output")),
                null, AgentConfig.defaults());

        AgentContext ctx = new AgentContext(execCtx, task, 0);

        assertThat(ctx.getMessages())
                .containsExactly("assistant:step one output", "assistant:step two output");
    }

    @Test
    @DisplayName("putVariable and getVariable — thread-safe")
    void variablesReadWrite() {
        AgentTask task = AgentTask.of("test");
        AgentContext ctx = new AgentContext(execCtx, task, 0);

        ctx.putVariable("key", "value");
        assertThat(ctx.getVariable("key")).isEqualTo("value");
    }

    @Test
    @DisplayName("getVariable with type filter")
    void getVariableTyped() {
        AgentTask task = AgentTask.of("test");
        AgentContext ctx = new AgentContext(execCtx, task, 0);

        ctx.putVariable("intKey", 42);
        ctx.putVariable("strKey", "hello");

        assertThat(ctx.getVariable("intKey", Integer.class)).isEqualTo(42);
        assertThat(ctx.getVariable("strKey", String.class)).isEqualTo("hello");
        assertThat(ctx.getVariable("intKey", String.class)).isNull();
        assertThat(ctx.getVariable("nonexistent", String.class)).isNull();
    }

    @Test
    @DisplayName("concurrent variable writes are thread-safe")
    void concurrentVariables() throws Exception {
        AgentTask task = AgentTask.of("test");
        AgentContext ctx = new AgentContext(execCtx, task, 0);

        int threads = 10;
        var executor = Executors.newFixedThreadPool(threads);
        var latch = new CountDownLatch(threads);

        for (int i = 0; i < threads; i++) {
            final int idx = i;
            executor.submit(() -> {
                ctx.putVariable("key-" + idx, idx);
                latch.countDown();
            });
        }
        latch.await();
        executor.shutdown();

        for (int i = 0; i < threads; i++) {
            assertThat(ctx.getVariable("key-" + i)).isEqualTo(i);
        }
    }

    @Test
    @DisplayName("addMessage appends to history")
    void messageHistory() {
        AgentTask task = AgentTask.of("test");
        AgentContext ctx = new AgentContext(execCtx, task, 0);

        ctx.addMessage("user", "hello");
        ctx.addMessage("assistant", "hi");

        assertThat(ctx.messageCount()).isEqualTo(2);
        assertThat(ctx.getMessages()).containsExactly("user:hello", "assistant:hi");
    }

    @Test
    @DisplayName("concurrent message writes are thread-safe")
    void concurrentMessages() throws Exception {
        AgentTask task = AgentTask.of("test");
        AgentContext ctx = new AgentContext(execCtx, task, 0);

        int threads = 10;
        var executor = Executors.newFixedThreadPool(threads);
        var latch = new CountDownLatch(threads);

        for (int i = 0; i < threads; i++) {
            final int idx = i;
            executor.submit(() -> {
                ctx.addMessage("user", "msg-" + idx);
                latch.countDown();
            });
        }
        latch.await();
        executor.shutdown();

        assertThat(ctx.messageCount()).isEqualTo(threads);
    }

    @Test
    @DisplayName("transitionTo succeeds for legal transition (CAS)")
    void legalTransition() {
        AgentTask task = AgentTask.of("test");
        AgentContext ctx = new AgentContext(execCtx, task, 0);

        assertThat(ctx.getState()).isEqualTo(AgentState.IDLE);
        assertThat(ctx.transitionTo(AgentState.PLANNING)).isTrue();
        assertThat(ctx.getState()).isEqualTo(AgentState.PLANNING);
    }

    @Test
    @DisplayName("transitionTo fails for illegal transition")
    void illegalTransition() {
        AgentTask task = AgentTask.of("test");
        AgentContext ctx = new AgentContext(execCtx, task, 0);

        assertThat(ctx.transitionTo(AgentState.ACTING)).isFalse();
        assertThat(ctx.getState()).isEqualTo(AgentState.IDLE);
    }

    @Test
    @DisplayName("transitionTo to same state returns true (idempotent)")
    void sameStateTransition() {
        AgentTask task = AgentTask.of("test");
        AgentContext ctx = new AgentContext(execCtx, task, 0);

        assertThat(ctx.transitionTo(AgentState.PLANNING)).isTrue();
        assertThat(ctx.getState()).isEqualTo(AgentState.PLANNING);
        assertThat(ctx.transitionTo(AgentState.PLANNING)).isTrue();
    }

    @Test
    @DisplayName("forceTransitionTo works for terminal states")
    void forceTransitionToTerminal() {
        AgentTask task = AgentTask.of("test");
        AgentContext ctx = new AgentContext(execCtx, task, 0);

        assertThat(ctx.forceTransitionTo(AgentState.COMPLETED)).isTrue();
        assertThat(ctx.getState()).isEqualTo(AgentState.COMPLETED);
    }

    @Test
    @DisplayName("forceTransitionTo fails for non-terminal target")
    void forceTransitionToNonTerminal() {
        AgentTask task = AgentTask.of("test");
        AgentContext ctx = new AgentContext(execCtx, task, 0);

        assertThat(ctx.forceTransitionTo(AgentState.PLANNING)).isFalse();
        assertThat(ctx.getState()).isEqualTo(AgentState.IDLE);
    }

    @Test
    @DisplayName("forceTransitionTo fails when already terminal")
    void forceTransitionWhenAlreadyTerminal() {
        AgentTask task = AgentTask.of("test");
        AgentContext ctx = new AgentContext(execCtx, task, 0);

        ctx.forceTransitionTo(AgentState.FAILED);
        assertThat(ctx.forceTransitionTo(AgentState.COMPLETED)).isFalse();
        assertThat(ctx.getState()).isEqualTo(AgentState.FAILED);
    }

    @Test
    @DisplayName("incrementStep and hasExceededMaxSteps")
    void stepTracking() {
        AgentTask task = new AgentTask("test", Map.of(), null, AgentConfig.of(3, Duration.ofMinutes(1)));
        AgentContext ctx = new AgentContext(execCtx, task, 0);

        assertThat(ctx.getCurrentStep()).isEqualTo(0);
        assertThat(ctx.hasExceededMaxSteps()).isFalse();

        ctx.incrementStep();
        ctx.incrementStep();
        ctx.incrementStep();

        assertThat(ctx.getCurrentStep()).isEqualTo(3);
        assertThat(ctx.hasExceededMaxSteps()).isTrue();
    }

    @Test
    @DisplayName("isTimedOut returns true after elapsed exceeds timeout")
    void timeoutDetection() {
        AgentConfig shortTimeout = new AgentConfig(15, Duration.ofMillis(1), 3, false, 3,
                com.yin.ai.platform.agent.planner.PlanningMode.TEMPLATE, false);
        AgentTask task = new AgentTask("test", Map.of(), null, shortTimeout);
        AgentContext ctx = new AgentContext(execCtx, task, 0);

        ctx.markStarted();
        // busy-wait to guarantee timeout
        long deadline = System.currentTimeMillis() + 50;
        while (System.currentTimeMillis() < deadline) {
            // spin
        }

        assertThat(ctx.isTimedOut()).isTrue();
    }

    @Test
    @DisplayName("isTimedOut returns false before startedAt is set")
    void notTimedOutBeforeStart() {
        AgentTask task = AgentTask.of("test");
        AgentContext ctx = new AgentContext(execCtx, task, 0);

        assertThat(ctx.isTimedOut()).isFalse();
        assertThat(ctx.elapsed()).isEqualTo(Duration.ZERO);
    }

    @Test
    @DisplayName("retry tracking: increment, reset, hasExceeded")
    void retryTracking() {
        AgentTask task = new AgentTask("test", Map.of(), null, AgentConfig.of(15, Duration.ofMinutes(1)));
        AgentContext ctx = new AgentContext(execCtx, task, 0);

        assertThat(ctx.getRetryCount()).isEqualTo(0);

        ctx.incrementRetryCount();
        ctx.incrementRetryCount();
        ctx.incrementRetryCount();

        assertThat(ctx.getRetryCount()).isEqualTo(3);
        assertThat(ctx.hasExceededRetries()).isTrue();

        ctx.resetRetryCount();
        assertThat(ctx.getRetryCount()).isEqualTo(0);
        assertThat(ctx.hasExceededRetries()).isFalse();
    }

    @Test
    @DisplayName("recordEvent adds to trace")
    void traceRecording() {
        AgentTask task = AgentTask.of("test");
        AgentContext ctx = new AgentContext(execCtx, task, 0);

        AgentEvent event = AgentEvent.of(AgentEventType.STEP_START, 1, "step-1");
        ctx.recordEvent(event);

        assertThat(ctx.getTrace()).hasSize(1);
        assertThat(ctx.getTrace().get(0).type()).isEqualTo(AgentEventType.STEP_START);
    }

    @Test
    @DisplayName("getTrace returns defensive copy")
    void traceDefensiveCopy() {
        AgentTask task = AgentTask.of("test");
        AgentContext ctx = new AgentContext(execCtx, task, 0);

        ctx.recordEvent(AgentEvent.of(AgentEventType.PLAN_START, 0, "test"));
        var trace1 = ctx.getTrace();
        var trace2 = ctx.getTrace();

        assertThat(trace1).isNotSameAs(trace2);
        assertThat(trace1).hasSize(1);
        assertThat(trace2).hasSize(1);
    }

    @Test
    @DisplayName("getMessages returns defensive copy")
    void messagesDefensiveCopy() {
        AgentTask task = AgentTask.of("test");
        AgentContext ctx = new AgentContext(execCtx, task, 0);

        ctx.addMessage("user", "hello");
        var messages1 = ctx.getMessages();
        var messages2 = ctx.getMessages();

        assertThat(messages1).isNotSameAs(messages2);
    }
}
