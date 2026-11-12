package com.yin.ai.platform.adapter.runtime;

import com.yin.ai.platform.agent.event.TaskEventType;
import com.yin.ai.platform.agent.pattern.AgentTaskRequest;
import com.yin.ai.platform.agent.pattern.RouteContext;
import com.yin.ai.platform.agent.pattern.UnifiedAgentRuntime;
import com.yin.ai.platform.agent.task.TaskSnapshot;
import com.yin.ai.platform.agent.task.TaskState;
import com.yin.ai.platform.core.agent.AgentEventListener;
import com.yin.ai.platform.core.agent.AgentInstruction;
import com.yin.ai.platform.core.agent.AgentRequest;
import com.yin.ai.platform.core.agent.AgentResult;
import com.yin.ai.platform.core.agent.AgentRun;
import com.yin.ai.platform.core.agent.AgentRuntime;
import com.yin.ai.platform.core.agent.AgentSnapshot;
import com.yin.ai.platform.core.agent.AgentStatus;
import com.yin.ai.platform.core.agent.AgentResumeRequest;
import reactor.core.publisher.Flux;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Transitional adapter exposing the existing unified Reactor runtime through the
 * framework-neutral core contract. New consumers depend on {@link AgentRuntime};
 * the Reactor implementation can then be replaced without another API migration.
 */
public final class UnifiedAgentRuntimeAdapter implements AgentRuntime {

    private final UnifiedAgentRuntime delegate;

    public UnifiedAgentRuntimeAdapter(UnifiedAgentRuntime delegate) {
        this.delegate = delegate;
    }

    @Override
    public AgentResult execute(AgentRequest request) {
        try {
            return submit(request, AgentEventListener.NOOP)
                    .completion().toCompletableFuture().join();
        } catch (CompletionException exception) {
            if (exception.getCause() instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw exception;
        }
    }

    @Override
    public AgentRun submit(AgentRequest request, AgentEventListener listener) {
        UnifiedAgentRuntime.TaskSubmission submission = delegate.submit(toLegacy(request));
        return adapt(submission.taskId(), submission.events(), listener);
    }

    @Override
    public AgentRun resume(AgentResumeRequest request, AgentEventListener listener) {
        return adapt(request.taskId(), delegate.resume(request.taskId(), request.overrides()), listener);
    }

    @Override
    public boolean cancel(String taskId) {
        TaskSnapshot current = delegate.getSnapshot(taskId);
        if (current == null || current.state().isTerminal()) {
            return false;
        }
        delegate.cancel(taskId, "cancelled through core runtime");
        return true;
    }

    @Override
    public Optional<AgentSnapshot> snapshot(String taskId) {
        return Optional.ofNullable(delegate.getSnapshot(taskId)).map(this::toCore);
    }

    @Override
    public List<AgentSnapshot> listChildren(String parentTaskId) {
        return delegate.listChildren(parentTaskId).stream().map(this::toCore).toList();
    }

    @Override
    public void queueInstruction(String taskId, AgentInstruction instruction) {
        if (!delegate.queueInstruction(taskId, instruction.content())) {
            throw new IllegalStateException("Task cannot accept instructions: " + taskId);
        }
    }

    private AgentRun adapt(String taskId,
                           Flux<com.yin.ai.platform.agent.event.AgentEvent> events,
                           AgentEventListener listener) {
        AgentEventListener safeListener = listener == null ? AgentEventListener.NOOP : listener;
        CompletableFuture<AgentResult> completion = new CompletableFuture<>();
        AtomicReference<String> output = new AtomicReference<>("");
        AtomicReference<String> error = new AtomicReference<>();
        AtomicReference<AgentStatus> status = new AtomicReference<>(AgentStatus.RUNNING);

        var subscription = events.subscribe(event -> {
            safeListener.onEvent(new com.yin.ai.platform.core.agent.AgentEvent(
                    event.id(), event.taskId(), event.type().name(), event.stepId(),
                    event.payload(), event.timestamp()));
            if (event.type() == TaskEventType.TASK_COMPLETED) {
                status.set(AgentStatus.COMPLETED);
                Object value = event.payload().get("result");
                if (value != null) output.set(value.toString());
            } else if (event.type() == TaskEventType.TASK_FAILED) {
                status.set(AgentStatus.FAILED);
                Object value = event.payload().get("error");
                if (value != null) error.set(value.toString());
            } else if (event.type() == TaskEventType.TASK_CANCELLED) {
                status.set(AgentStatus.CANCELLED);
            } else if (event.type() == TaskEventType.TASK_WAITING) {
                status.set(AgentStatus.WAITING);
            }
        }, completion::completeExceptionally, () -> {
            AgentSnapshot snapshot = snapshot(taskId).orElse(null);
            AgentStatus finalStatus = snapshot != null ? snapshot.status() : status.get();
            Map<String, Object> metadata = snapshot == null ? Map.of() : snapshot.variables();
            completion.complete(new AgentResult(taskId, finalStatus, output.get(), error.get(), metadata));
        });

        return new AgentRun() {
            @Override
            public String taskId() {
                return taskId;
            }

            @Override
            public CompletableFuture<AgentResult> completion() {
                return completion;
            }

            @Override
            public boolean cancel() {
                boolean cancelled = UnifiedAgentRuntimeAdapter.this.cancel(taskId);
                if (cancelled) {
                    subscription.dispose();
                    completion.complete(new AgentResult(taskId, AgentStatus.CANCELLED,
                            null, "cancelled", Map.of()));
                }
                return cancelled;
            }
        };
    }

    private AgentTaskRequest toLegacy(AgentRequest request) {
        Map<String, String> metadata = new LinkedHashMap<>(request.metadata());
        if (request.executionContext() != null) {
            metadata.putIfAbsent("traceId", request.executionContext().traceId());
            putIfPresent(metadata, "tenantId", request.executionContext().tenantId());
            putIfPresent(metadata, "caller", request.executionContext().caller());
            putIfPresent(metadata, "operation", request.executionContext().operation());
        }
        boolean deepMode = Boolean.parseBoolean(metadata.getOrDefault("deepMode", "false"));
        Map<String, Object> hints = new LinkedHashMap<>();
        putObjectIfPresent(hints, "forcedMode", metadata.get("forcedMode"));
        RouteContext routeContext = new RouteContext(request.userId(), request.sessionId(),
                deepMode, Map.copyOf(hints));
        return new AgentTaskRequest(request.goal(), request.userId(), request.sessionId(),
                routeContext, Map.copyOf(metadata), request.systemPrompt(),
                request.temperature(), request.model());
    }

    private AgentSnapshot toCore(TaskSnapshot snapshot) {
        return new AgentSnapshot(snapshot.taskId(), snapshot.parentTaskId(), snapshot.goal(),
                toCore(snapshot.state()), snapshot.mode().name(), snapshot.createdAt(),
                snapshot.updatedAt(), snapshot.variables());
    }

    private AgentStatus toCore(TaskState state) {
        return switch (state) {
            case CREATED -> AgentStatus.CREATED;
            case PLANNING, RUNNING -> AgentStatus.RUNNING;
            case WAITING -> AgentStatus.WAITING;
            case COMPLETED -> AgentStatus.COMPLETED;
            case FAILED -> AgentStatus.FAILED;
            case CANCELLED -> AgentStatus.CANCELLED;
        };
    }

    private static void putIfPresent(Map<String, String> target, String key, String value) {
        if (value != null && !value.isBlank()) target.put(key, value);
    }

    private static void putObjectIfPresent(Map<String, Object> target, String key, Object value) {
        if (value != null) target.put(key, value);
    }
}
