package com.yin.ai.platform.adapter.runtime;

import com.yin.ai.platform.agent.event.AgentEvent;
import com.yin.ai.platform.agent.event.EventPublisher;
import com.yin.ai.platform.agent.event.TaskEventType;
import com.yin.ai.platform.agent.pattern.AgentExecutionContext;
import com.yin.ai.platform.agent.pattern.AgentPatternBridge;
import com.yin.ai.platform.agent.task.TaskSnapshot;
import com.yin.ai.platform.agent.task.TaskState;
import com.yin.ai.platform.core.agent.AgentRequest;
import com.yin.ai.platform.core.agent.AgentStatus;
import com.yin.ai.platform.core.agent.approval.ApprovalPolicy;
import com.yin.ai.platform.core.agent.clarification.ModelResearchClarifier;
import com.yin.ai.platform.core.agent.clarification.ResearchClarifier;
import com.yin.ai.platform.core.agent.checkpoint.AgentCheckpointStore;
import com.yin.ai.platform.core.agent.pattern.AgentPattern;
import com.yin.ai.platform.core.agent.pattern.DeepResearchAgentPattern;
import com.yin.ai.platform.core.agent.pattern.DirectAgentPattern;
import com.yin.ai.platform.core.agent.pattern.PatternContext;
import com.yin.ai.platform.core.agent.pattern.PatternResult;
import com.yin.ai.platform.core.agent.pattern.PlanExecuteAgentPattern;
import com.yin.ai.platform.core.agent.pattern.ReActAgentPattern;
import com.yin.ai.platform.core.agent.plan.DefaultPlanExecutor;
import com.yin.ai.platform.core.agent.plan.ModelStepReflector;
import com.yin.ai.platform.core.agent.plan.StepReflector;
import com.yin.ai.platform.core.agent.research.DefaultResearchSynthesisPolicy;
import com.yin.ai.platform.core.agent.research.ResearchSynthesisPolicy;
import com.yin.ai.platform.core.context.CancellationToken;
import com.yin.ai.platform.core.context.ExecutionContext;
import com.yin.ai.platform.core.model.ModelCall;
import com.yin.ai.platform.core.model.ModelClient;
import com.yin.ai.platform.core.model.ModelEventListener;
import com.yin.ai.platform.core.model.ModelMessage;
import com.yin.ai.platform.core.model.ModelRequest;
import com.yin.ai.platform.core.model.ModelResponse;
import com.yin.ai.platform.core.model.ModelUsage;
import com.yin.ai.platform.core.tool.ToolRegistry;
import com.yin.ai.platform.gateway.TokenUsage;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Runs the pure-Java core patterns inside the existing Redis/SSE task shell.
 * No core implementation is persisted into a legacy context snapshot.
 */
@SuppressWarnings("deprecation")
public final class CoreAgentPatternBridge implements AgentPatternBridge {

    private final ModelClient modelClient;
    private final ToolRegistry toolRegistry;
    private final CorePatternDelegationPolicy policy;
    private final ApprovalPolicy approvalPolicy;
    private final ResearchClarifier researchClarifier;
    private final StepReflector stepReflector;
    private final ResearchSynthesisPolicy researchSynthesisPolicy;
    private final Map<com.yin.ai.platform.core.agent.pattern.AgentMode, AgentPattern> patterns;

    public CoreAgentPatternBridge(ModelClient modelClient, ToolRegistry toolRegistry,
                                  CorePatternDelegationPolicy policy) {
        this(modelClient, toolRegistry, policy, ApprovalPolicy.NONE,
                ModelResearchClarifier.standard());
    }

    public CoreAgentPatternBridge(ModelClient modelClient, ToolRegistry toolRegistry,
                                  CorePatternDelegationPolicy policy,
                                  ApprovalPolicy approvalPolicy) {
        this(modelClient, toolRegistry, policy, approvalPolicy,
                ModelResearchClarifier.standard());
    }

    public CoreAgentPatternBridge(ModelClient modelClient, ToolRegistry toolRegistry,
                                  CorePatternDelegationPolicy policy,
                                  ApprovalPolicy approvalPolicy,
                                  ResearchClarifier researchClarifier) {
        this(modelClient, toolRegistry, policy, approvalPolicy, researchClarifier,
                ModelStepReflector.standard(), DefaultResearchSynthesisPolicy.standard());
    }

    public CoreAgentPatternBridge(ModelClient modelClient, ToolRegistry toolRegistry,
                                  CorePatternDelegationPolicy policy,
                                  ApprovalPolicy approvalPolicy,
                                  ResearchClarifier researchClarifier,
                                  StepReflector stepReflector,
                                  ResearchSynthesisPolicy researchSynthesisPolicy) {
        this.modelClient = Objects.requireNonNull(modelClient, "modelClient");
        this.toolRegistry = Objects.requireNonNull(toolRegistry, "toolRegistry");
        this.policy = policy == null ? CorePatternDelegationPolicy.disabled() : policy;
        this.approvalPolicy = approvalPolicy == null ? ApprovalPolicy.NONE : approvalPolicy;
        this.researchClarifier = researchClarifier == null
                ? ModelResearchClarifier.standard() : researchClarifier;
        this.stepReflector = stepReflector == null
                ? ModelStepReflector.standard() : stepReflector;
        this.researchSynthesisPolicy = researchSynthesisPolicy == null
                ? DefaultResearchSynthesisPolicy.standard() : researchSynthesisPolicy;
        DefaultPlanExecutor planExecutor = new DefaultPlanExecutor(
                this.policy.maxPlanRounds(), this.policy.maxStepRetries());
        this.patterns = Map.of(
                com.yin.ai.platform.core.agent.pattern.AgentMode.DIRECT,
                new DirectAgentPattern(),
                com.yin.ai.platform.core.agent.pattern.AgentMode.REACT,
                new ReActAgentPattern(this.policy.maxReActIterations()),
                com.yin.ai.platform.core.agent.pattern.AgentMode.PLAN_EXECUTE,
                PlanExecuteAgentPattern.standard(planExecutor),
                com.yin.ai.platform.core.agent.pattern.AgentMode.DEEP_RESEARCH,
                DeepResearchAgentPattern.standard(planExecutor, this.researchClarifier,
                        this.stepReflector, this.researchSynthesisPolicy));
    }

    @Override
    public boolean supports(com.yin.ai.platform.core.agent.mode.AgentMode mode,
                            AgentExecutionContext context, boolean resuming) {
        if (!policy.enabled() || mode == null || context == null) return false;
        com.yin.ai.platform.core.agent.pattern.AgentMode coreMode = coreMode(mode);
        if (coreMode == null || !policy.modes().contains(coreMode)) return false;
        Object override = context.getVariable(policy.requestOverrideKey());
        if (isFalse(override)) return false;
        if (!resuming) return true;
        if (!Boolean.TRUE.equals(context.getVariable("coreDelegated"))) return false;
        try {
            return new LegacyContextAgentCheckpointStore(context)
                    .load(context.getTaskId()).isPresent();
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    @Override
    public Flux<AgentEvent> execute(com.yin.ai.platform.core.agent.mode.AgentMode mode,
                                    AgentExecutionContext context) {
        com.yin.ai.platform.core.agent.pattern.AgentMode coreMode = coreMode(mode);
        AgentPattern pattern = coreMode == null ? null : patterns.get(coreMode);
        if (pattern == null) {
            return Flux.error(new IllegalArgumentException(
                    "Core delegation does not support mode: " + mode));
        }
        return Flux.create(sink -> {
            EventPublisher publisher = context.getEventPublisher();
            MeteredModelClient meteredModel = new MeteredModelClient(modelClient);
            AgentCheckpointStore checkpointStore =
                    new LegacyContextAgentCheckpointStore(context);
            try {
                prepareState(context, coreMode);
                context.putVariable("coreDelegated", Boolean.TRUE);
                context.putVariable("corePatternMode", coreMode.name());
                emit(publisher, sink, new AgentEvent(null, context.getTaskId(),
                        TaskEventType.PROGRESS, null,
                        Map.of("message", "Core pattern selected", "coreMode", coreMode.name()),
                        Instant.now()));

                PatternResult result = pattern.execute(new PatternContext(
                        context.getTaskId(), coreRequest(context), coreExecutionContext(context),
                        meteredModel, toolRegistry, initialMessages(context),
                        (type, payload) -> projectEvent(type, payload, context, publisher, sink),
                        checkpointStore, approvalPolicy));

                if (result.status() == AgentStatus.WAITING) {
                    if (context.getState() == TaskState.PLANNING
                            || context.getState() == TaskState.RUNNING) {
                        context.transitionTo(TaskState.WAITING);
                    }
                    accumulateUsage(context, meteredModel.usage());
                    String waitingPhase = text(result.metadata().get("phase"));
                    if (!"clarification".equals(waitingPhase)) {
                        emit(publisher, sink, AgentEvent.taskWaiting(context.getTaskId(),
                                defaultText(waitingPhase, "approval"),
                                approvalId(result.metadata())));
                    }
                    sink.complete();
                    return;
                }

                if (context.getState() == TaskState.PLANNING) {
                    context.transitionTo(TaskState.RUNNING);
                }
                context.putVariable("finalOutput", result.output());
                if (coreMode == com.yin.ai.platform.core.agent.pattern.AgentMode.DIRECT) {
                    context.putVariable("response", result.output());
                }
                accumulateUsage(context, meteredModel.usage());
                emit(publisher, sink, AgentEvent.stepCompleted(
                        context.getTaskId(), "core-execution", result.output()));
                sink.complete();
            } catch (Throwable error) {
                accumulateUsage(context, meteredModel.usage());
                sink.error(error);
            }
        });
    }

    private void projectEvent(String type, Map<String, Object> payload,
                              AgentExecutionContext context, EventPublisher publisher,
                              reactor.core.publisher.FluxSink<AgentEvent> sink) {
        Map<String, Object> safePayload = payload == null
                ? new LinkedHashMap<>() : new LinkedHashMap<>(payload);
        safePayload.put("coreEvent", type);
        String taskId = context.getTaskId();
        String stepId = text(safePayload.get("stepId"));
        AgentEvent event;
        switch (type) {
            case "plan.generation.started", "research.planning.started" -> {
                if (context.getState() == TaskState.CREATED) {
                    context.transitionTo(TaskState.PLANNING);
                }
                event = event(taskId, TaskEventType.TASK_PLANNING, null, safePayload);
            }
            case "plan.generated" -> {
                if (context.getState() == TaskState.PLANNING) {
                    context.transitionTo(TaskState.RUNNING);
                }
                event = event(taskId, TaskEventType.PLAN_CREATED, null, safePayload);
            }
            case "plan.replanned" -> event = event(
                    taskId, TaskEventType.PLAN_UPDATED, null, safePayload);
            case "plan.step.started" -> event = AgentEvent.stepStarted(taskId,
                    defaultText(stepId, "core-step"),
                    defaultText(text(safePayload.get("description")), "执行 Core 计划步骤"));
            case "plan.step.completed" -> event = AgentEvent.stepCompleted(taskId,
                    defaultText(stepId, "core-step"), text(safePayload.get("output")));
            case "plan.step.failed" -> event = AgentEvent.stepFailed(taskId,
                    defaultText(stepId, "core-step"), text(safePayload.get("error")));
            case "plan.step.skipped" -> event = AgentEvent.stepSkipped(taskId,
                    defaultText(stepId, "core-step"), text(safePayload.get("reason")));
            case "plan.step.retrying" -> event = event(
                    taskId, TaskEventType.STEP_PROGRESS, stepId, safePayload);
            case "tool.started" -> event = AgentEvent.toolStarted(taskId, stepId,
                    text(safePayload.get("tool")), "Core 工具调用");
            case "tool.completed" -> event = AgentEvent.toolCompleted(taskId, stepId,
                    text(safePayload.get("tool")), "Core 工具调用完成");
            case "tool.failed" -> event = AgentEvent.toolFailed(taskId, stepId,
                    text(safePayload.get("tool")), "Core 工具调用失败",
                    text(safePayload.get("observation")));
            case "research.execution.started" -> {
                if (context.getState() == TaskState.PLANNING) {
                    context.transitionTo(TaskState.RUNNING);
                }
                event = event(taskId, TaskEventType.TASK_RUNNING, null, safePayload);
            }
            case "model.started", "plan.synthesis.started", "research.synthesis.started" ->
                    event = event(taskId, TaskEventType.REASONING_STARTED, stepId, safePayload);
            case "research.synthesis.completed" -> event = event(
                    taskId, TaskEventType.REASONING_COMPLETED, stepId, safePayload);
            case "clarification.required" -> {
                if (context.getState() == TaskState.PLANNING
                        || context.getState() == TaskState.RUNNING) {
                    context.transitionTo(TaskState.WAITING);
                }
                safePayload.put("phase", "clarification");
                context.putVariable("phase", "clarification_waiting");
                context.putVariable("clarificationId", safePayload.get("clarificationId"));
                context.putVariable("clarification.questions", safePayload.get("questions"));
                context.putVariable("clarification.options", safePayload.get("options"));
                event = event(taskId, TaskEventType.TASK_WAITING, null, safePayload);
            }
            case "clarification.resolved" -> {
                context.putVariable("phase", "clarification_resolved");
                event = event(taskId, TaskEventType.CONTEXT_UPDATED, null, safePayload);
            }
            case "approval.required" -> {
                if (context.getState() == TaskState.PLANNING
                        || context.getState() == TaskState.RUNNING) {
                    context.transitionTo(TaskState.WAITING);
                }
                event = AgentEvent.approvalRequired(taskId,
                        defaultText(stepId, "core-step"),
                        text(safePayload.get("approvalId")),
                        text(safePayload.get("action")),
                        text(safePayload.get("risk")));
            }
            case "approval.resolved" -> event = AgentEvent.approvalResolved(taskId,
                    defaultText(stepId, "core-step"),
                    text(safePayload.get("approvalId")),
                    text(safePayload.get("decision")),
                    text(safePayload.get("resolvedBy")));
            default -> event = event(taskId, TaskEventType.PROGRESS, stepId, safePayload);
        }
        emit(publisher, sink, event);
    }

    private AgentRequest coreRequest(AgentExecutionContext context) {
        Map<String, String> metadata = new LinkedHashMap<>();
        context.snapshot().variables().forEach((key, value) -> {
            if (value instanceof String text) metadata.put(key, text);
            else if (value instanceof Number || value instanceof Boolean) {
                metadata.put(key, String.valueOf(value));
            }
        });
        return new AgentRequest(context.getGoal(), context.getUserId(), context.getSessionId(),
                metadata, stringVariable(context, "systemPrompt"),
                numberVariable(context, "temperature"), stringVariable(context, "model"), null);
    }

    private List<ModelMessage> initialMessages(AgentExecutionContext context) {
        List<ModelMessage> messages = new ArrayList<>();
        String systemPrompt = stringVariable(context, "systemPrompt");
        if (hasText(systemPrompt)) messages.add(ModelMessage.system(systemPrompt));
        String goal = context.getGoal();
        String sessionContext = stringVariable(context, "sessionContext");
        if (hasText(sessionContext)) goal = sessionContext + "\n\n" + goal;
        messages.add(ModelMessage.user(goal));
        return List.copyOf(messages);
    }

    private ExecutionContext coreExecutionContext(AgentExecutionContext context) {
        com.yin.ai.platform.engine.ExecutionContext legacy = context.getAgentContext() == null
                ? null : context.getAgentContext().getExecCtx();
        Map<String, Object> attributes = new LinkedHashMap<>();
        String traceId = context.getTaskId();
        String conversationId = context.getSessionId();
        String userId = context.getUserId();
        String caller = "legacy-unified-agent-runtime";
        String operation = "agent." + context.getAgentMode().name().toLowerCase();
        Instant createdAt = context.getCreatedAt();
        String tenantId = null;
        if (legacy != null) {
            if (hasText(legacy.traceId())) traceId = legacy.traceId();
            if (hasText(legacy.conversationId())) conversationId = legacy.conversationId();
            if (legacy.userId() != null) userId = legacy.userId().toString();
            if (hasText(legacy.caller())) caller = legacy.caller();
            if (hasText(legacy.operation())) operation = legacy.operation();
            if (legacy.createdAt() != null) createdAt = legacy.createdAt();
            if (legacy.attributes() != null) attributes.putAll(legacy.attributes());
            if (legacy.mdcContext() != null) {
                attributes.put("mdc", Map.copyOf(legacy.mdcContext()));
                tenantId = legacy.mdcContext().get("tenantId");
            }
            if (legacy.securityContext() != null
                    && legacy.securityContext().getAuthentication() != null) {
                Set<String> authorities = legacy.securityContext().getAuthentication()
                        .getAuthorities().stream().map(Object::toString)
                        .collect(java.util.stream.Collectors.toUnmodifiableSet());
                attributes.put("roles", authorities);
                attributes.put("permissions", authorities);
            }
        }
        return new ExecutionContext(traceId, conversationId, tenantId, userId, caller,
                operation, createdAt, attributes, cancellationToken(context));
    }

    private CancellationToken cancellationToken(AgentExecutionContext context) {
        return () -> {
            if (Thread.currentThread().isInterrupted()) return true;
            if (context.getTaskEngine() == null) return false;
            TaskSnapshot snapshot = context.getTaskEngine().getSnapshot(context.getTaskId());
            return snapshot != null && snapshot.state() == TaskState.CANCELLED;
        };
    }

    private void prepareState(AgentExecutionContext context,
                              com.yin.ai.platform.core.agent.pattern.AgentMode mode) {
        boolean resuming = Boolean.TRUE.equals(context.getVariable("resume"));
        if (resuming && (context.getState() == TaskState.FAILED
                || context.getState() == TaskState.WAITING
                || context.getState() == TaskState.PLANNING)) {
            context.transitionTo(TaskState.RUNNING);
            return;
        }
        if (context.getState() == TaskState.CREATED) {
            context.transitionTo(mode == com.yin.ai.platform.core.agent.pattern.AgentMode.PLAN_EXECUTE
                    || mode == com.yin.ai.platform.core.agent.pattern.AgentMode.DEEP_RESEARCH
                    ? TaskState.PLANNING : TaskState.RUNNING);
        }
    }

    private static void accumulateUsage(AgentExecutionContext context, ModelUsage usage) {
        if (usage == null || usage.totalTokens() == 0) return;
        context.accumulateTokenUsage(new TokenUsage(toInt(usage.inputTokens()),
                toInt(usage.outputTokens()), toInt(usage.totalTokens())));
    }

    private static int toInt(long value) {
        return (int) Math.min(Integer.MAX_VALUE, Math.max(0, value));
    }

    private static com.yin.ai.platform.core.agent.pattern.AgentMode coreMode(
            com.yin.ai.platform.core.agent.mode.AgentMode mode) {
        if (mode == null || mode == com.yin.ai.platform.core.agent.mode.AgentMode.MULTI_AGENT) {
            return null;
        }
        return com.yin.ai.platform.core.agent.pattern.AgentMode.valueOf(mode.name());
    }

    private static void emit(EventPublisher publisher,
                             reactor.core.publisher.FluxSink<AgentEvent> sink,
                             AgentEvent event) {
        publisher.publish(event);
        sink.next(event);
    }

    private static AgentEvent event(String taskId, TaskEventType type, String stepId,
                                    Map<String, Object> payload) {
        return new AgentEvent(null, taskId, type, stepId, Map.copyOf(payload), Instant.now());
    }

    private static String stringVariable(AgentExecutionContext context, String key) {
        return text(context.getVariable(key));
    }

    private static Double numberVariable(AgentExecutionContext context, String key) {
        Object value = context.getVariable(key);
        if (value instanceof Number number) return number.doubleValue();
        try {
            return value == null ? null : Double.valueOf(value.toString());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static boolean isFalse(Object value) {
        return value instanceof Boolean bool ? !bool
                : value != null && "false".equalsIgnoreCase(value.toString().trim());
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String approvalId(Map<String, Object> metadata) {
        if (metadata == null) return null;
        Object raw = metadata.get("approval");
        return raw instanceof Map<?, ?> approval
                ? text(approval.get("approvalId")) : null;
    }

    private static String defaultText(String value, String fallback) {
        return hasText(value) ? value : fallback;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static final class MeteredModelClient implements ModelClient {
        private final ModelClient delegate;
        private final AtomicLong inputTokens = new AtomicLong();
        private final AtomicLong outputTokens = new AtomicLong();
        private final AtomicLong totalTokens = new AtomicLong();

        private MeteredModelClient(ModelClient delegate) {
            this.delegate = delegate;
        }

        @Override
        public ModelResponse execute(ModelRequest request, ExecutionContext context) {
            return record(delegate.execute(request, context));
        }

        @Override
        public ModelCall submit(ModelRequest request, ExecutionContext context,
                                ModelEventListener listener) {
            ModelCall call = delegate.submit(request, context, listener);
            CompletionStage<ModelResponse> completion = call.completion().thenApply(this::record);
            return new ModelCall() {
                @Override public CompletionStage<ModelResponse> completion() { return completion; }
                @Override public boolean cancel() { return call.cancel(); }
            };
        }

        private ModelResponse record(ModelResponse response) {
            if (response == null) return null;
            ModelUsage usage = response.usage();
            inputTokens.addAndGet(usage.inputTokens());
            outputTokens.addAndGet(usage.outputTokens());
            totalTokens.addAndGet(usage.totalTokens());
            return response;
        }

        private ModelUsage usage() {
            return new ModelUsage(inputTokens.get(), outputTokens.get(), totalTokens.get());
        }
    }
}
