package com.yin.ai.platform.agent;

import com.yin.ai.platform.agent.executor.AgentExecutor;
import com.yin.ai.platform.agent.executor.StepResult;
import com.yin.ai.platform.agent.observer.Observation;
import com.yin.ai.platform.agent.observer.Observer;
import com.yin.ai.platform.agent.planner.Plan;
import com.yin.ai.platform.agent.planner.Planner;
import com.yin.ai.platform.agent.planner.Step;
import com.yin.ai.platform.agent.reflector.Decision;
import com.yin.ai.platform.agent.reflector.Reflector;
import com.yin.ai.platform.engine.ExecutionContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ReActLoop")
class ReActLoopTest {

    private ExecutionContext execCtx() {
        return new ExecutionContext(
                UUID.randomUUID().toString(), null, null, null, null,
                Map.of(), null, null, Instant.now(), Map.of());
    }

    private AgentContext ctx(AgentConfig config) {
        AgentTask task = new AgentTask("test goal", Map.of(), null, config);
        return new AgentContext(execCtx(), task, 0);
    }

    // ---- Normal flow ----

    @Test
    @DisplayName("enter with NoOp SPIs reaches COMPLETED")
    void normalFlowCompletes() {
        ReActLoop loop = new ReActLoop(
                new com.yin.ai.platform.agent.planner.NoOpPlanner(),
                new com.yin.ai.platform.agent.executor.NoOpAgentExecutor(),
                new com.yin.ai.platform.agent.observer.NoOpObserver(),
                new com.yin.ai.platform.agent.reflector.NoOpReflector());

        AgentContext ctx = ctx(AgentConfig.defaults());
        AgentResult result = loop.enter(ctx);

        assertThat(result.success()).isTrue();
        assertThat(ctx.getState()).isEqualTo(AgentState.COMPLETED);
        assertThat(result.trace()).isNotEmpty();
    }

    @Test
    @DisplayName("checkpoint callback uses external task key")
    void checkpointCallbackUsesExternalTaskKey() {
        ReActLoop loop = new ReActLoop(
                new com.yin.ai.platform.agent.planner.NoOpPlanner(),
                new com.yin.ai.platform.agent.executor.NoOpAgentExecutor(),
                new com.yin.ai.platform.agent.observer.NoOpObserver(),
                new com.yin.ai.platform.agent.reflector.NoOpReflector());
        AtomicReference<String> checkpointTaskId = new AtomicReference<>();
        loop.setCheckpointCallback((taskId, step, context) -> checkpointTaskId.set(taskId));

        AgentTask task = new AgentTask("checkpoint goal", Map.of(
                "taskId", "platform-task-1",
                "checkpointTaskId", "creator-task-1"), null, AgentConfig.defaults());
        AgentContext context = new AgentContext(execCtx(), task, 0);

        loop.enter(context);

        assertThat(checkpointTaskId).hasValue("creator-task-1");
    }

    @Test
    @DisplayName("checkpoint resume skips planner and starts from next step")
    void checkpointResumeSkipsCompletedSteps() {
        AtomicInteger plannerCalls = new AtomicInteger();
        AtomicInteger executorCalls = new AtomicInteger();
        Planner planner = c -> {
            plannerCalls.incrementAndGet();
            return Plan.error("planner should not be called during resume");
        };
        List<Step> steps = List.of(Step.of("s1", "completed"), Step.of("s2", "pending"));
        AgentExecutor executor = (step, context) -> {
            executorCalls.incrementAndGet();
            return StepResult.ok(step.id(), 0);
        };
        ReActLoop loop = new ReActLoop(planner, executor,
                new com.yin.ai.platform.agent.observer.NoOpObserver(),
                (observation, context) -> Decision.COMPLETE);

        AgentTask task = new AgentTask("resume goal", Map.of(
                "agent.resumePlan", Plan.ofSteps(steps),
                "agent.resumeStepIndex", 1), null, AgentConfig.defaults());
        AgentContext context = new AgentContext(execCtx(), task, 0);
        AgentResult result = loop.enter(context);

        assertThat(result.success()).isTrue();
        assertThat(plannerCalls).hasValue(0);
        assertThat(executorCalls).hasValue(1);
        assertThat(result.trace()).extracting(StepTrace::action).containsExactly("pending");
        assertThat(context.getCurrentStep()).isEqualTo(2);
    }

    @Test
    @DisplayName("enter with empty plan completes immediately")
    void emptyPlanCompletes() {
        Planner emptyPlanner = c -> Plan.empty();
        ReActLoop loop = new ReActLoop(emptyPlanner,
                new com.yin.ai.platform.agent.executor.NoOpAgentExecutor(),
                new com.yin.ai.platform.agent.observer.NoOpObserver(),
                new com.yin.ai.platform.agent.reflector.NoOpReflector());

        AgentContext ctx = ctx(AgentConfig.defaults());
        AgentResult result = loop.enter(ctx);

        assertThat(result.success()).isTrue();
        assertThat(ctx.getState()).isEqualTo(AgentState.COMPLETED);
    }

    @Test
    @DisplayName("enter with direct result plan returns result immediately")
    void directResultPlan() {
        Planner directPlanner = c -> Plan.direct("direct answer");
        ReActLoop loop = new ReActLoop(directPlanner,
                new com.yin.ai.platform.agent.executor.NoOpAgentExecutor(),
                new com.yin.ai.platform.agent.observer.NoOpObserver(),
                new com.yin.ai.platform.agent.reflector.NoOpReflector());

        AgentContext ctx = ctx(AgentConfig.defaults());
        AgentResult result = loop.enter(ctx);

        assertThat(result.success()).isTrue();
        assertThat(result.result()).isEqualTo("direct answer");
        assertThat(ctx.getState()).isEqualTo(AgentState.COMPLETED);
    }

    // ---- maxSteps ----

    @Test
    @DisplayName("maxSteps=3 auto-terminates after 3 steps")
    void maxStepsEnforcement() {
        // Create a plan with many steps
        List<Step> steps = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            steps.add(Step.of("step-" + i, "step " + i));
        }
        Planner multiStepPlanner = c -> Plan.ofSteps(steps);
        // Reflector always says CONTINUE so it keeps going
        Reflector continueReflector = (obs, c) -> Decision.CONTINUE;

        ReActLoop loop = new ReActLoop(multiStepPlanner,
                new com.yin.ai.platform.agent.executor.NoOpAgentExecutor(),
                new com.yin.ai.platform.agent.observer.NoOpObserver(),
                continueReflector);

        AgentConfig config = new AgentConfig(3, Duration.ofMinutes(1), 3, false, 3,
                com.yin.ai.platform.agent.planner.PlanningMode.TEMPLATE, false);
        AgentContext ctx = ctx(config);
        AgentResult result = loop.enter(ctx);

        assertThat(ctx.getState()).isEqualTo(AgentState.COMPLETED);
        assertThat(result.stepCount()).isEqualTo(3);
        assertThat(result.trace()).hasSize(3);
    }

    // ---- Retry ----

    @Test
    @DisplayName("RETRY decision re-executes same step within limit")
    void retryWithinLimit() {
        List<Step> steps = List.of(Step.of("s1", "step 1"));
        Planner planner = c -> Plan.ofSteps(steps);

        // Executor always fails
        AgentExecutor failingExecutor = (step, c) -> StepResult.fail("error", 0);
        // Observer reports failure
        Observer failObserver = (sr, c) -> Observation.failure("failed");
        // Reflector: retry once only
        java.util.concurrent.atomic.AtomicInteger retryCount = new java.util.concurrent.atomic.AtomicInteger(0);
        Reflector retryOnceReflector = (obs, c) -> {
            if (retryCount.getAndIncrement() == 0) {
                c.incrementRetryCount();
                return Decision.RETRY;
            }
            return Decision.COMPLETE;
        };

        ReActLoop loop = new ReActLoop(planner, failingExecutor, failObserver, retryOnceReflector);
        AgentContext ctx = ctx(AgentConfig.defaults());
        AgentResult result = loop.enter(ctx);

        assertThat(result.success()).isTrue();
        // The step was executed once, retried once = 2 step traces
        assertThat(result.trace()).hasSize(2);
    }

    // ---- Cancel ----

    @Test
    @DisplayName("cancel mid-loop transitions to CANCELLED")
    void cancelMidLoop() throws Exception {
        List<Step> steps = List.of(Step.of("s1", "step 1"), Step.of("s2", "step 2"));
        Planner planner = c -> Plan.ofSteps(steps);

        CountDownLatch enteredStep = new CountDownLatch(1);
        CountDownLatch releaseExecutor = new CountDownLatch(1);
        AgentExecutor blockingExecutor = (step, c) -> {
            enteredStep.countDown();
            try {
                releaseExecutor.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return StepResult.ok("done", 0);
        };
        Reflector continueReflector = (obs, c) -> Decision.CONTINUE;

        ReActLoop loop = new ReActLoop(planner, blockingExecutor,
                new com.yin.ai.platform.agent.observer.NoOpObserver(), continueReflector);

        AgentContext ctx = ctx(AgentConfig.defaults());

        Thread loopThread = new Thread(() -> loop.enter(ctx));
        loopThread.start();

        assertThat(enteredStep.await(5, TimeUnit.SECONDS)).isTrue();

        // Cancel while loop is blocked in executor
        loop.cancel(ctx);
        releaseExecutor.countDown();

        loopThread.join(5000);
        assertThat(ctx.getState()).isEqualTo(AgentState.CANCELLED);
    }

    // ---- Timeout ----

    @Test
    @DisplayName("timeout detected during loop terminates with TIMEOUT state")
    void timeoutDuringLoop() {
        List<Step> steps = List.of(
                Step.of("s1", "step 1"),
                Step.of("s2", "step 2"),
                Step.of("s3", "step 3"));

        Planner planner = c -> Plan.ofSteps(steps);
        // Executor that sleeps to ensure timeout triggers
        AgentExecutor slowExecutor = (step, c) -> {
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return StepResult.ok("done", 5);
        };
        // Reflector always says CONTINUE so the loop keeps going
        Reflector continueReflector = (obs, c) -> Decision.CONTINUE;

        ReActLoop loop = new ReActLoop(planner, slowExecutor,
                new com.yin.ai.platform.agent.observer.NoOpObserver(), continueReflector);

        AgentConfig config = new AgentConfig(100, Duration.ofMillis(1), 3, false, 3,
                com.yin.ai.platform.agent.planner.PlanningMode.TEMPLATE, false);
        AgentContext ctx = ctx(config);
        AgentResult result = loop.enter(ctx);

        assertThat(ctx.getState()).isEqualTo(AgentState.TIMEOUT);
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("timeout");
    }

    // ---- Error handling ----

    @Test
    @DisplayName("planner throwing exception results in FAILED state")
    void plannerException() {
        Planner throwingPlanner = c -> { throw new RuntimeException("plan error"); };
        ReActLoop loop = new ReActLoop(throwingPlanner,
                new com.yin.ai.platform.agent.executor.NoOpAgentExecutor(),
                new com.yin.ai.platform.agent.observer.NoOpObserver(),
                new com.yin.ai.platform.agent.reflector.NoOpReflector());

        AgentContext ctx = ctx(AgentConfig.defaults());
        AgentResult result = loop.enter(ctx);

        assertThat(ctx.getState()).isEqualTo(AgentState.FAILED);
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("plan error");
    }

    @Test
    @DisplayName("executor throwing exception results in FAILED state")
    void executorException() {
        List<Step> steps = List.of(Step.of("s1", "step 1"));
        Planner planner = c -> Plan.ofSteps(steps);
        AgentExecutor throwingExecutor = (step, c) -> { throw new RuntimeException("exec error"); };

        ReActLoop loop = new ReActLoop(planner, throwingExecutor,
                new com.yin.ai.platform.agent.observer.NoOpObserver(),
                new com.yin.ai.platform.agent.reflector.NoOpReflector());

        AgentContext ctx = ctx(AgentConfig.defaults());
        AgentResult result = loop.enter(ctx);

        assertThat(ctx.getState()).isEqualTo(AgentState.FAILED);
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("exec error");
    }

    // ---- Streaming ----

    @Test
    @DisplayName("enterStream emits events for each phase")
    void streamingEmitsEvents() {
        ReActLoop loop = new ReActLoop(
                new com.yin.ai.platform.agent.planner.NoOpPlanner(),
                new com.yin.ai.platform.agent.executor.NoOpAgentExecutor(),
                new com.yin.ai.platform.agent.observer.NoOpObserver(),
                new com.yin.ai.platform.agent.reflector.NoOpReflector());

        AgentContext ctx = ctx(AgentConfig.defaults());
        List<AgentEvent> events = loop.enterStream(ctx).collectList().block(Duration.ofSeconds(10));

        assertThat(events).isNotNull();
        assertThat(events).isNotEmpty();
        // Should have at least: PLAN_START, AGENT_COMPLETE
        boolean hasAgentComplete = events.stream()
                .anyMatch(e -> e.type() == AgentEventType.AGENT_COMPLETE);
        assertThat(hasAgentComplete).isTrue();
    }

    @Test
    @DisplayName("enterStream emits error event on failure")
    void streamingErrorEvent() {
        Planner throwingPlanner = c -> { throw new RuntimeException("stream error"); };
        ReActLoop loop = new ReActLoop(throwingPlanner,
                new com.yin.ai.platform.agent.executor.NoOpAgentExecutor(),
                new com.yin.ai.platform.agent.observer.NoOpObserver(),
                new com.yin.ai.platform.agent.reflector.NoOpReflector());

        AgentContext ctx = ctx(AgentConfig.defaults());
        List<AgentEvent> events = loop.enterStream(ctx).collectList().block(Duration.ofSeconds(10));

        assertThat(events).isNotNull();
        boolean hasError = events.stream()
                .anyMatch(e -> e.type() == AgentEventType.AGENT_ERROR);
        assertThat(hasError).isTrue();
    }

    // ---- Final result aggregation ----

    @Test
    @DisplayName("final result uses last non-blank assistant message, skipping empty echoes")
    void finalResultAggregatesLastNonBlankAssistantMessage() {
        List<Step> steps = List.of(Step.of("s1", "step 1"), Step.of("s2", "step 2"));
        Planner planner = c -> Plan.ofSteps(steps);
        AgentExecutor executor = (step, context) -> {
            if ("s1".equals(step.id())) {
                context.addMessage("assistant", "[web_search] {\"results\":[]}");
            } else {
                context.addMessage("assistant", "");
            }
            return StepResult.ok("done-" + step.id(), 0);
        };
        AtomicInteger reflections = new AtomicInteger();
        Reflector reflector = (obs, c) ->
                reflections.incrementAndGet() < 2 ? Decision.CONTINUE : Decision.COMPLETE;

        ReActLoop loop = new ReActLoop(planner, executor,
                new com.yin.ai.platform.agent.observer.NoOpObserver(), reflector);

        AgentContext ctx = ctx(AgentConfig.defaults());
        AgentResult result = loop.enter(ctx);

        assertThat(result.success()).isTrue();
        assertThat(result.result()).isEqualTo("[web_search] {\"results\":[]}");
    }

    @Test
    @DisplayName("final result falls back to step-trace summary when no assistant message")
    void finalResultFallsBackToTraceSummary() {
        List<Step> steps = List.of(Step.of("s1", "搜集资料"));
        Planner planner = c -> Plan.ofSteps(steps);
        AgentExecutor executor = (step, context) -> StepResult.ok("市场数据：规模 100 亿", 0);
        Reflector completeReflector = (obs, c) -> Decision.COMPLETE;

        ReActLoop loop = new ReActLoop(planner, executor,
                new com.yin.ai.platform.agent.observer.NoOpObserver(), completeReflector);

        AgentContext ctx = ctx(AgentConfig.defaults());
        AgentResult result = loop.enter(ctx);

        assertThat(result.success()).isTrue();
        assertThat(result.result()).isNotEmpty();
        assertThat(result.result()).contains("步骤 1");
        assertThat(result.result()).contains("市场数据：规模 100 亿");
    }
}
