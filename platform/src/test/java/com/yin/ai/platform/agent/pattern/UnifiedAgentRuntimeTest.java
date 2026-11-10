package com.yin.ai.platform.agent.pattern;

import com.yin.ai.platform.agent.event.AgentEvent;
import com.yin.ai.platform.agent.event.EventPublisher;
import com.yin.ai.platform.agent.event.TaskEventType;
import com.yin.ai.platform.agent.pattern.Artifact;
import com.yin.ai.platform.agent.task.ContextStore;
import com.yin.ai.platform.agent.task.TaskEngine;
import com.yin.ai.platform.agent.task.TaskHandle;
import com.yin.ai.platform.agent.task.TaskSnapshot;
import com.yin.ai.platform.agent.task.TaskState;
import com.yin.ai.platform.agent.task.TaskTokenAccountant;
import com.yin.ai.platform.agent.task.TaskTokenSummary;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import com.yin.ai.platform.core.agent.mode.AgentMode;

class UnifiedAgentRuntimeTest {

    @Test
    void delegatesFreshExecutionToConfiguredCoreBridgeInsteadOfLegacyPattern() {
        AgentRouter router = mock(AgentRouter.class);
        when(router.route(any(), any())).thenReturn(AgentMode.DIRECT);
        TaskEngine taskEngine = mock(TaskEngine.class);
        when(taskEngine.create(any())).thenReturn(new TaskHandle(
                "core-delegated", AgentMode.DIRECT, TaskState.CREATED, Instant.now()));
        when(taskEngine.getSnapshot("core-delegated")).thenReturn(new TaskSnapshot(
                "core-delegated", "draft", "user", "session", AgentMode.DIRECT,
                TaskState.RUNNING, null, 0, Map.of(), Instant.now(), Instant.now(), null));
        ContextStore contextStore = mock(ContextStore.class);
        EventPublisher publisher = mock(EventPublisher.class);
        AtomicInteger legacyExecutions = new AtomicInteger();
        AgentPattern legacy = new AgentPattern() {
            @Override public AgentMode mode() { return AgentMode.DIRECT; }
            @Override public Flux<AgentEvent> execute(AgentExecutionContext ctx) {
                legacyExecutions.incrementAndGet();
                return Flux.empty();
            }
        };
        UnifiedAgentRuntime runtime = new UnifiedAgentRuntime(
                router, taskEngine, List.of(legacy), contextStore, publisher);
        runtime.setPatternBridge(new AgentPatternBridge() {
            @Override
            public boolean supports(AgentMode mode, AgentExecutionContext context,
                                    boolean resuming) {
                return !resuming && mode == AgentMode.DIRECT;
            }

            @Override
            public Flux<AgentEvent> execute(AgentMode mode, AgentExecutionContext context) {
                context.transitionTo(TaskState.RUNNING);
                context.putVariable("finalOutput", "from core");
                return Flux.empty();
            }
        });

        List<AgentEvent> events = runtime.submit(AgentTaskRequest.of(
                "draft", "user", "session")).events().collectList().block();

        assertThat(legacyExecutions).hasValue(0);
        assertThat(events).extracting(AgentEvent::type)
                .containsExactly(TaskEventType.TASK_CREATED, TaskEventType.TASK_COMPLETED);
        assertThat(events.getLast().payload()).containsEntry("result", "from core");
    }

    @Test
    void resumesCoreDelegatedTaskThroughBridgeInsteadOfLegacyPattern() {
        AgentRouter router = mock(AgentRouter.class);
        TaskEngine taskEngine = mock(TaskEngine.class);
        AtomicReference<TaskState> taskState = new AtomicReference<>(TaskState.FAILED);
        when(taskEngine.getSnapshot("core-resume")).thenAnswer(invocation -> new TaskSnapshot(
                "core-resume", "continue", "user", "session", AgentMode.PLAN_EXECUTE,
                taskState.get(), null, 0, Map.of(), Instant.now(), Instant.now(), null));
        doAnswer(invocation -> {
            taskState.set(TaskState.RUNNING);
            return true;
        }).when(taskEngine).tryResume("core-resume");
        doAnswer(invocation -> {
            taskState.set(invocation.getArgument(1));
            return null;
        }).when(taskEngine).transitionTo(eq("core-resume"), any(TaskState.class));
        ContextStore contextStore = mock(ContextStore.class);
        when(contextStore.loadSnapshot("core-resume")).thenReturn(new ContextSnapshot(
                "core-resume", "session", "user", AgentMode.PLAN_EXECUTE,
                TaskState.FAILED, null, 1,
                Map.of("goal", "continue", "coreDelegated", true,
                        "core.checkpoint", Map.of("schemaVersion", 1)),
                List.of(), List.of(), null, Instant.now(), Instant.now()));
        EventPublisher publisher = mock(EventPublisher.class);
        AtomicInteger legacyExecutions = new AtomicInteger();
        AgentPattern legacy = new AgentPattern() {
            @Override public AgentMode mode() { return AgentMode.PLAN_EXECUTE; }
            @Override public boolean resumable() { return true; }
            @Override public Flux<AgentEvent> execute(AgentExecutionContext ctx) {
                legacyExecutions.incrementAndGet();
                return Flux.empty();
            }
        };
        UnifiedAgentRuntime runtime = new UnifiedAgentRuntime(
                router, taskEngine, List.of(legacy), contextStore, publisher);
        runtime.setPatternBridge(new AgentPatternBridge() {
            @Override
            public boolean supports(AgentMode mode, AgentExecutionContext context,
                                    boolean resuming) {
                return resuming && mode == AgentMode.PLAN_EXECUTE;
            }

            @Override
            public Flux<AgentEvent> execute(AgentMode mode, AgentExecutionContext context) {
                context.transitionTo(TaskState.RUNNING);
                context.putVariable("finalOutput", "resumed by core");
                return Flux.empty();
            }
        });

        List<AgentEvent> events = runtime.resume("core-resume").collectList().block();

        assertThat(legacyExecutions).hasValue(0);
        assertThat(taskState).hasValue(TaskState.COMPLETED);
        assertThat(events).extracting(AgentEvent::type)
                .containsExactly(TaskEventType.TASK_RESUMED, TaskEventType.TASK_COMPLETED);
        assertThat(events.getLast().payload()).containsEntry("result", "resumed by core");
    }

    @Test
    void exposesAccountedTaskTokenUsageForPersistence() {
        AgentRouter router = mock(AgentRouter.class);
        TaskEngine taskEngine = mock(TaskEngine.class);
        ContextStore contextStore = mock(ContextStore.class);
        EventPublisher publisher = mock(EventPublisher.class);
        TaskTokenAccountant accountant = mock(TaskTokenAccountant.class);
        when(accountant.getSummary("task-usage")).thenReturn(new TaskTokenSummary(
                "task-usage", 321L, 2L, Map.of(), Map.of(), Map.of(), false, false));

        UnifiedAgentRuntime runtime = new UnifiedAgentRuntime(
                router, taskEngine, List.of(), contextStore, publisher, null, accountant);

        assertThat(runtime.getTokenUsage("task-usage").getTotalTokens()).isEqualTo(321);
        assertThat(runtime.getTokenUsage("task-usage").getPromptTokens()).isNull();
        assertThat(runtime.getTokenUsage("task-usage").getCompletionTokens()).isNull();
    }

    @Test
    void preparePersistsCreatedSnapshotAndExplicitResumeStartsSameTask() {
        AgentRouter router = mock(AgentRouter.class);
        when(router.route(any(), any())).thenReturn(AgentMode.DIRECT);
        TaskEngine taskEngine = mock(TaskEngine.class);
        when(taskEngine.create(any())).thenReturn(new TaskHandle(
                "prepared-1", AgentMode.DIRECT, TaskState.CREATED, Instant.now()));

        ContextStore contextStore = mock(ContextStore.class);
        AtomicReference<ContextSnapshot> persisted = new AtomicReference<>();
        doAnswer(invocation -> {
            persisted.set(invocation.getArgument(1));
            return null;
        }).when(contextStore).saveSnapshot(eq("prepared-1"), any(ContextSnapshot.class));
        EventPublisher publisher = mock(EventPublisher.class);
        AtomicInteger executions = new AtomicInteger();
        AgentPattern pattern = new AgentPattern() {
            @Override public AgentMode mode() { return AgentMode.DIRECT; }
            @Override public Flux<AgentEvent> execute(AgentExecutionContext ctx) {
                executions.incrementAndGet();
                return Flux.never();
            }
        };
        UnifiedAgentRuntime runtime = new UnifiedAgentRuntime(
                router, taskEngine, List.of(pattern), contextStore, publisher);
        AgentTaskRequest request = new AgentTaskRequest("draft", "42", "session-1",
                RouteContext.of("42", "session-1"), Map.of(), null, null, null);

        UnifiedAgentRuntime.TaskSubmission prepared = runtime.prepare(request);

        assertThat(prepared.taskId()).isEqualTo("prepared-1");
        assertThat(prepared.events().collectList().block())
                .extracting(AgentEvent::type)
                .containsExactly(TaskEventType.TASK_CREATED);
        assertThat(persisted.get()).isNotNull();
        assertThat(executions).hasValue(0);
        verify(taskEngine, never()).start("prepared-1");

        when(taskEngine.getSnapshot("prepared-1")).thenReturn(new TaskSnapshot(
                "prepared-1", "draft", "42", "session-1", AgentMode.DIRECT,
                TaskState.CREATED, null, 0, Map.of(), Instant.now(), Instant.now(), null));
        when(contextStore.loadSnapshot("prepared-1")).thenReturn(persisted.get());

        assertThat(runtime.canResume("prepared-1")).isTrue();
        runtime.resume("prepared-1");
        verify(taskEngine).start("prepared-1");
        assertThat(executions).hasValue(1);
    }

    @Test
    void submitPropagatesSessionContextAndReturnsFinalArtifactContent() {
        AgentRouter router = mock(AgentRouter.class);
        when(router.route(any(), any())).thenReturn(AgentMode.PLAN_EXECUTE);

        TaskEngine taskEngine = mock(TaskEngine.class);
        when(taskEngine.create(any())).thenReturn(new TaskHandle(
                "task-1", AgentMode.PLAN_EXECUTE, TaskState.CREATED, Instant.now()));

        ContextStore contextStore = mock(ContextStore.class);
        EventPublisher eventPublisher = mock(EventPublisher.class);
        AtomicReference<AgentExecutionContext> captured = new AtomicReference<>();
        AgentPattern pattern = new AgentPattern() {
            @Override
            public AgentMode mode() {
                return AgentMode.PLAN_EXECUTE;
            }

            @Override
            public Flux<AgentEvent> execute(AgentExecutionContext ctx) {
                captured.set(ctx);
                ctx.putVariable("finalOutput", "final research report");
                return Flux.empty();
            }

            @Override
            public boolean resumable() {
                return true;
            }

            @Override
            public int priority() {
                return 0;
            }
        };

        UnifiedAgentRuntime runtime = new UnifiedAgentRuntime(router, taskEngine,
                List.of(pattern), contextStore, eventPublisher);
        AgentTaskRequest request = new AgentTaskRequest("continue the research", "42", "session-1",
                RouteContext.of("42", "session-1"),
                Map.of("sessionContext", "上一轮已经完成资料搜集"), null, null, null);

        List<AgentEvent> events = runtime.submit(request).events().collectList().block();

        assertThat(captured.get()).isNotNull();
        assertThat(captured.get().getVariable("sessionContext"))
                .isEqualTo("上一轮已经完成资料搜集");
        assertThat(captured.get().getAgentContext().getSessionId()).isEqualTo("session-1");
        assertThat(events.stream()
                .filter(event -> event.type() == TaskEventType.TASK_COMPLETED)
                .toList()).singleElement().satisfies(event -> {
            assertThat(event.type()).isEqualTo(TaskEventType.TASK_COMPLETED);
            assertThat(event.payload()).containsEntry("result", "final research report");
        });
    }

    @Test
    void submitFallsBackToLastArtifactWhenNoExplicitFinalOutput() {
        AgentRouter router = mock(AgentRouter.class);
        when(router.route(any(), any())).thenReturn(AgentMode.PLAN_EXECUTE);

        TaskEngine taskEngine = mock(TaskEngine.class);
        when(taskEngine.create(any())).thenReturn(new TaskHandle(
                "task-2", AgentMode.PLAN_EXECUTE, TaskState.CREATED, Instant.now()));

        ContextStore contextStore = mock(ContextStore.class);
        EventPublisher eventPublisher = mock(EventPublisher.class);
        AgentPattern pattern = new AgentPattern() {
            @Override
            public AgentMode mode() {
                return AgentMode.PLAN_EXECUTE;
            }

            @Override
            public Flux<AgentEvent> execute(AgentExecutionContext ctx) {
                ctx.addArtifact(Artifact.of("report.md", "markdown",
                        "final artifact body", "synthesis"));
                return Flux.empty();
            }

            @Override
            public boolean resumable() {
                return true;
            }

            @Override
            public int priority() {
                return 0;
            }
        };

        UnifiedAgentRuntime runtime = new UnifiedAgentRuntime(router, taskEngine,
                List.of(pattern), contextStore, eventPublisher);
        AgentTaskRequest request = new AgentTaskRequest("continue the research", "42", "session-1",
                RouteContext.of("42", "session-1"),
                Map.of("sessionContext", "上一轮已经完成资料搜集"), null, null, null);

        List<AgentEvent> events = runtime.submit(request).events().collectList().block();

        assertThat(events.stream()
                .filter(event -> event.type() == TaskEventType.TASK_COMPLETED)
                .toList()).singleElement().satisfies(event -> {
            assertThat(event.type()).isEqualTo(TaskEventType.TASK_COMPLETED);
            assertThat(event.payload()).containsEntry("result", "final artifact body");
        });
    }

    @Test
    void submitSynchronizesPlanningTaskBeforeCompletion() {
        AgentRouter router = mock(AgentRouter.class);
        when(router.route(any(), any())).thenReturn(AgentMode.PLAN_EXECUTE);

        TaskEngine taskEngine = mock(TaskEngine.class);
        AtomicReference<TaskState> taskState = new AtomicReference<>(TaskState.CREATED);
        when(taskEngine.create(any())).thenReturn(new TaskHandle(
                "task-3", AgentMode.PLAN_EXECUTE, TaskState.CREATED, Instant.now()));
        doAnswer(invocation -> {
            taskState.set(TaskState.PLANNING);
            return null;
        }).when(taskEngine).start("task-3");
        when(taskEngine.getSnapshot(eq("task-3"))).thenAnswer(invocation -> new TaskSnapshot(
                "task-3", "write an article", "42", "session-1", AgentMode.PLAN_EXECUTE,
                taskState.get(), null, 0, Map.of(), Instant.now(), Instant.now(), null));
        doAnswer(invocation -> {
            TaskState target = invocation.getArgument(1);
            TaskState current = taskState.get();
            if (!current.canTransitionTo(target)) {
                throw new IllegalStateException("Illegal transition: " + current + " -> " + target);
            }
            taskState.set(target);
            return null;
        }).when(taskEngine).transitionTo(eq("task-3"), any(TaskState.class));

        ContextStore contextStore = mock(ContextStore.class);
        EventPublisher eventPublisher = mock(EventPublisher.class);
        AgentPattern pattern = new AgentPattern() {
            @Override
            public AgentMode mode() {
                return AgentMode.PLAN_EXECUTE;
            }

            @Override
            public Flux<AgentEvent> execute(AgentExecutionContext ctx) {
                ctx.transitionTo(TaskState.PLANNING);
                ctx.transitionTo(TaskState.RUNNING);
                return Flux.empty();
            }

            @Override
            public boolean resumable() {
                return true;
            }

            @Override
            public int priority() {
                return 0;
            }
        };

        UnifiedAgentRuntime runtime = new UnifiedAgentRuntime(router, taskEngine,
                List.of(pattern), contextStore, eventPublisher);
        AgentTaskRequest request = new AgentTaskRequest("write an article", "42", "session-1",
                RouteContext.of("42", "session-1"), Map.of(), null, null, null);

        runtime.submit(request).events().collectList().block();

        assertThat(taskState).hasValue(TaskState.COMPLETED);
    }
}
