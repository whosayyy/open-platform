package com.yin.ai.platform.core.agent;

import com.yin.ai.platform.core.agent.approval.ApprovalPolicy;
import com.yin.ai.platform.core.agent.clarification.ModelResearchClarifier;
import com.yin.ai.platform.core.agent.clarification.ResearchClarifier;
import com.yin.ai.platform.core.agent.checkpoint.AgentCheckpointStore;
import com.yin.ai.platform.core.agent.checkpoint.InMemoryAgentCheckpointStore;
import com.yin.ai.platform.core.agent.pattern.AgentMode;
import com.yin.ai.platform.core.agent.pattern.AgentPattern;
import com.yin.ai.platform.core.agent.pattern.AgentPatternRouter;
import com.yin.ai.platform.core.agent.pattern.DeepResearchAgentPattern;
import com.yin.ai.platform.core.agent.pattern.DefaultAgentPatternRouter;
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
import com.yin.ai.platform.core.agent.store.AgentRunRecord;
import com.yin.ai.platform.core.agent.store.AgentRunStore;
import com.yin.ai.platform.core.agent.store.InMemoryAgentRunStore;
import com.yin.ai.platform.core.context.CancellationToken;
import com.yin.ai.platform.core.context.ExecutionContext;
import com.yin.ai.platform.core.model.ModelClient;
import com.yin.ai.platform.core.model.ModelMessage;
import com.yin.ai.platform.core.tool.ToolRegistry;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Small framework-free runtime suitable for direct Java consumers.
 *
 * <p>It provides the canonical lifecycle, event and cancellation boundaries,
 * plus built-in direct, ReAct, plan/execute and deep-research policies. Every
 * policy uses only core model/tool ports and can be replaced through the builder.</p>
 */
public final class DefaultAgentRuntime implements AgentRuntime {

    private final ModelClient modelClient;
    private final ToolRegistry toolRegistry;
    private final Executor executor;
    private final AgentPatternRouter patternRouter;
    private final AgentCheckpointStore checkpointStore;
    private final ApprovalPolicy approvalPolicy;
    private final AgentRunStore runStore;
    private final Map<AgentMode, AgentPattern> patterns;
    private final ConcurrentHashMap<String, RunState> runs = new ConcurrentHashMap<>();

    private DefaultAgentRuntime(Builder builder) {
        this.modelClient = Objects.requireNonNull(builder.modelClient, "modelClient");
        this.toolRegistry = Objects.requireNonNull(builder.toolRegistry, "toolRegistry");
        this.executor = Objects.requireNonNull(builder.executor, "executor");
        this.patternRouter = Objects.requireNonNull(builder.patternRouter, "patternRouter");
        this.checkpointStore = Objects.requireNonNull(builder.checkpointStore, "checkpointStore");
        this.approvalPolicy = Objects.requireNonNull(builder.approvalPolicy, "approvalPolicy");
        this.runStore = Objects.requireNonNull(builder.runStore, "runStore");
        DefaultPlanExecutor planExecutor = new DefaultPlanExecutor(builder.maxPlanRounds,
                builder.maxStepRetries);
        Map<AgentMode, AgentPattern> configured = new LinkedHashMap<>();
        register(configured, new DirectAgentPattern());
        register(configured, new ReActAgentPattern(builder.maxReActIterations));
        register(configured, PlanExecuteAgentPattern.standard(planExecutor));
        register(configured, DeepResearchAgentPattern.standard(
                planExecutor, builder.researchClarifier, builder.stepReflector,
                builder.researchSynthesisPolicy));
        builder.customPatterns.forEach(pattern -> register(configured, pattern));
        this.patterns = Map.copyOf(configured);
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public AgentResult execute(AgentRequest request) {
        Objects.requireNonNull(request, "request");
        RunState state = newState(UUID.randomUUID().toString(), request,
                AgentEventListener.NOOP);
        return run(state);
    }

    @Override
    public AgentRun submit(AgentRequest request, AgentEventListener listener) {
        Objects.requireNonNull(request, "request");
        RunState state = newState(UUID.randomUUID().toString(), request,
                listener == null ? AgentEventListener.NOOP : listener);
        startAsync(state);
        return handle(state);
    }

    @Override
    public AgentRun resume(AgentResumeRequest request, AgentEventListener listener) {
        Objects.requireNonNull(request, "request");
        RunState state = findOrRestore(request.taskId());
        if (state.executing.get()
                || state.completion != null && !state.completion.isDone()) {
            throw new IllegalStateException("Agent task is already running: " + request.taskId());
        }
        Map<String, String> metadata = new LinkedHashMap<>(state.request.metadata());
        metadata.putAll(request.overrides());
        ExecutionContext context = request.executionContext() == null
                ? state.request.executionContext() : request.executionContext();
        state.request = new AgentRequest(state.request.goal(), state.request.userId(),
                state.request.sessionId(), metadata, state.request.systemPrompt(),
                state.request.temperature(), state.request.model(), context);
        state.listener = listener == null ? AgentEventListener.NOOP : listener;
        state.cancelled.set(false);
        state.error = null;
        state.output = null;
        touch(state);
        persist(state);
        startAsync(state);
        return handle(state);
    }

    @Override
    public boolean cancel(String taskId) {
        RunState state = findOrRestoreOrNull(taskId);
        if (state == null || terminal(state.status)) {
            return false;
        }
        boolean changed = state.cancelled.compareAndSet(false, true);
        if (changed) {
            touch(state);
            persist(state);
            emit(state, "agent.cancel.requested", Map.of());
        }
        return changed;
    }

    @Override
    public Optional<AgentSnapshot> snapshot(String taskId) {
        RunState active = runs.get(taskId);
        if (active != null) return Optional.of(snapshotOf(active));
        return runStore.load(taskId).map(this::snapshotOf);
    }

    @Override
    public List<AgentSnapshot> listChildren(String parentTaskId) {
        return runStore.listByParent(parentTaskId).stream()
                .map(this::snapshotOf)
                .toList();
    }

    @Override
    public void queueInstruction(String taskId, AgentInstruction instruction) {
        Objects.requireNonNull(instruction, "instruction");
        RunState state = findOrRestore(taskId);
        state.instructions.add(instruction);
        touch(state);
        persist(state);
        emit(state, "agent.instruction.queued", Map.of("type", instruction.type()));
    }

    private RunState newState(String taskId, AgentRequest request,
                              AgentEventListener listener) {
        RunState state = new RunState(taskId, parentTaskId(request), request, listener);
        RunState existing = runs.putIfAbsent(taskId, state);
        if (existing != null) {
            throw new IllegalStateException("Duplicate agent task: " + taskId);
        }
        if (!runStore.create(recordOf(state, 0L))) {
            runs.remove(taskId, state);
            throw new IllegalStateException("Duplicate agent task: " + taskId);
        }
        return state;
    }

    private RunState findOrRestore(String taskId) {
        RunState state = findOrRestoreOrNull(taskId);
        if (state == null) {
            throw new IllegalArgumentException("Unknown agent task: " + taskId);
        }
        return state;
    }

    private RunState findOrRestoreOrNull(String taskId) {
        if (taskId == null || taskId.isBlank()) return null;
        RunState active = runs.get(taskId);
        if (active != null) return active;
        AgentRunRecord record = runStore.load(taskId).orElse(null);
        if (record == null) return null;
        RunState restored = new RunState(record, AgentEventListener.NOOP);
        RunState existing = runs.putIfAbsent(taskId, restored);
        return existing == null ? restored : existing;
    }

    private void startAsync(RunState state) {
        state.completion = CompletableFuture.supplyAsync(() -> run(state), executor);
    }

    private AgentResult run(RunState state) {
        if (!state.executing.compareAndSet(false, true)) {
            throw new IllegalStateException("Agent task is already running: " + state.taskId);
        }
        try {
            state.status = AgentStatus.RUNNING;
            touch(state);
            persist(state);
            emit(state, "agent.started", Map.of("goal", state.request.goal()));
            ExecutionContext context = executionContext(state);
            context.cancellationToken().throwIfCancellationRequested();
            List<ModelMessage> messages = new ArrayList<>();
            if (hasText(state.request.systemPrompt())) {
                messages.add(ModelMessage.system(state.request.systemPrompt()));
            }
            messages.add(ModelMessage.user(state.request.goal()));
            AgentInstruction instruction;
            boolean consumedInstructions = false;
            while ((instruction = state.instructions.poll()) != null) {
                consumedInstructions = true;
                messages.add(ModelMessage.user("[Instruction: " + instruction.type() + "] "
                        + nullToEmpty(instruction.content())));
            }
            if (consumedInstructions) {
                touch(state);
                persist(state);
            }
            AgentMode mode = patternRouter.route(state.request);
            AgentPattern pattern = Optional.ofNullable(patterns.get(mode))
                    .orElseThrow(() -> new IllegalStateException(
                            "No core agent pattern registered for mode: " + mode));
            state.mode = mode.name();
            touch(state);
            persist(state);
            emit(state, "agent.pattern.selected", Map.of("mode", mode.name()));
            PatternResult patternResult = pattern.execute(new PatternContext(state.taskId,
                    state.request, context, modelClient, toolRegistry, messages,
                    (type, payload) -> emit(state, type, payload), checkpointStore,
                    approvalPolicy));

            state.output = patternResult.status() == AgentStatus.WAITING
                    ? null : patternResult.output();
            state.status = patternResult.status();
            touch(state);
            persist(state);
            if (state.status == AgentStatus.WAITING) {
                emit(state, "agent.waiting", patternResult.metadata());
            } else {
                emit(state, "agent.completed", Map.of("output", nullToEmpty(state.output)));
            }
            return new AgentResult(state.taskId, state.status, state.output, null,
                    patternResult.metadata());
        } catch (java.util.concurrent.CancellationException exception) {
            state.status = AgentStatus.CANCELLED;
            state.error = exception.getMessage();
            touch(state);
            persistSafely(state, exception);
            emit(state, "agent.cancelled", Map.of());
            return new AgentResult(state.taskId, state.status, null, state.error, Map.of());
        } catch (RuntimeException exception) {
            state.status = AgentStatus.FAILED;
            state.error = hasText(exception.getMessage())
                    ? exception.getMessage() : exception.getClass().getSimpleName();
            touch(state);
            persistSafely(state, exception);
            emit(state, "agent.failed", Map.of("error", state.error));
            return new AgentResult(state.taskId, state.status, null, state.error, Map.of());
        } finally {
            state.executing.set(false);
        }
    }

    private ExecutionContext executionContext(RunState state) {
        AgentRequest request = state.request;
        ExecutionContext source = request.executionContext();
        if (source == null) {
            source = new ExecutionContext(state.taskId, request.sessionId(), null,
                    request.userId(), "core:agent", "execute", Instant.now(),
                    Map.of(), CancellationToken.NONE);
        }
        CancellationToken original = source.cancellationToken();
        CancellationToken combined = () -> state.cancelled.get()
                || original.isCancellationRequested();
        return new ExecutionContext(source.traceId(), source.conversationId(),
                source.tenantId(), source.userId(), source.caller(), source.operation(),
                source.createdAt(), source.attributes(), combined);
    }

    private AgentRun handle(RunState state) {
        return new AgentRun() {
            @Override
            public String taskId() {
                return state.taskId;
            }

            @Override
            public CompletionStage<AgentResult> completion() {
                return state.completion;
            }

            @Override
            public boolean cancel() {
                return DefaultAgentRuntime.this.cancel(state.taskId);
            }
        };
    }

    private AgentSnapshot snapshotOf(RunState state) {
        synchronized (state) {
            return snapshotOf(recordOf(state, state.revision));
        }
    }

    private AgentSnapshot snapshotOf(AgentRunRecord record) {
        Map<String, Object> variables = new LinkedHashMap<>(record.request().metadata());
        variables.put("queuedInstructions", record.queuedInstructions().size());
        variables.put("cancellationRequested", record.cancellationRequested());
        variables.put("runRevision", record.revision());
        if (record.output() != null) {
            variables.put("output", record.output());
        }
        if (record.error() != null) {
            variables.put("error", record.error());
        }
        checkpointStore.load(record.taskId()).ifPresent(checkpoint -> {
            variables.put("checkpointPhase", checkpoint.phase());
            variables.put("checkpointRevision", checkpoint.revision());
        });
        return new AgentSnapshot(record.taskId(), record.parentTaskId(),
                record.request().goal(), record.status(), record.mode(),
                record.createdAt(), record.updatedAt(), variables);
    }

    private void persist(RunState state) {
        synchronized (state) {
            long nextRevision = state.revision + 1L;
            if (!runStore.update(recordOf(state, nextRevision))) {
                throw new IllegalStateException("Stale agent task state: " + state.taskId);
            }
            state.revision = nextRevision;
        }
    }

    private void persistSafely(RunState state, RuntimeException cause) {
        try {
            persist(state);
        } catch (RuntimeException persistenceFailure) {
            cause.addSuppressed(persistenceFailure);
        }
    }

    private static AgentRunRecord recordOf(RunState state, long revision) {
        return new AgentRunRecord(state.taskId, state.parentTaskId, state.request,
                state.status, state.mode, state.createdAt, state.updatedAt,
                state.output, state.error, List.copyOf(state.instructions),
                state.cancelled.get(), revision);
    }

    private static String parentTaskId(AgentRequest request) {
        String value = request.metadata().get("parentTaskId");
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static void register(Map<AgentMode, AgentPattern> target, AgentPattern pattern) {
        Objects.requireNonNull(pattern, "pattern");
        target.put(pattern.mode(), pattern);
    }

    private static void emit(RunState state, String type, Map<String, Object> payload) {
        AgentEvent event = new AgentEvent(state.taskId + ":" + state.sequence.incrementAndGet(),
                state.taskId, type, null, payload, Instant.now());
        try {
            state.listener.onEvent(event);
        } catch (RuntimeException ignored) {
            // Runtime observers must not be able to abort the agent.
        }
    }

    private static void touch(RunState state) {
        state.updatedAt = Instant.now();
    }

    private static boolean terminal(AgentStatus status) {
        return status == AgentStatus.COMPLETED || status == AgentStatus.FAILED
                || status == AgentStatus.CANCELLED;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    public static final class Builder {
        private ModelClient modelClient;
        private ToolRegistry toolRegistry;
        private Executor executor = ForkJoinPool.commonPool();
        private AgentPatternRouter patternRouter = new DefaultAgentPatternRouter();
        private AgentCheckpointStore checkpointStore = new InMemoryAgentCheckpointStore();
        private AgentRunStore runStore = new InMemoryAgentRunStore();
        private ApprovalPolicy approvalPolicy = ApprovalPolicy.NONE;
        private ResearchClarifier researchClarifier = ModelResearchClarifier.standard();
        private StepReflector stepReflector = ModelStepReflector.standard();
        private ResearchSynthesisPolicy researchSynthesisPolicy =
                DefaultResearchSynthesisPolicy.standard();
        private final List<AgentPattern> customPatterns = new ArrayList<>();
        private int maxReActIterations = 8;
        private int maxPlanRounds = 32;
        private int maxStepRetries = 2;

        private Builder() {
        }

        public Builder model(ModelClient value) {
            this.modelClient = value;
            return this;
        }

        public Builder tools(ToolRegistry value) {
            this.toolRegistry = value;
            return this;
        }

        public Builder executor(Executor value) {
            this.executor = value;
            return this;
        }

        public Builder router(AgentPatternRouter value) {
            this.patternRouter = value;
            return this;
        }

        public Builder checkpoints(AgentCheckpointStore value) {
            this.checkpointStore = Objects.requireNonNull(value, "value");
            return this;
        }

        public Builder runStore(AgentRunStore value) {
            this.runStore = Objects.requireNonNull(value, "value");
            return this;
        }

        public Builder approvals(ApprovalPolicy value) {
            this.approvalPolicy = Objects.requireNonNull(value, "value");
            return this;
        }

        public Builder researchClarifier(ResearchClarifier value) {
            this.researchClarifier = Objects.requireNonNull(value, "value");
            return this;
        }

        public Builder stepReflector(StepReflector value) {
            this.stepReflector = Objects.requireNonNull(value, "value");
            return this;
        }

        public Builder researchSynthesisPolicy(ResearchSynthesisPolicy value) {
            this.researchSynthesisPolicy = Objects.requireNonNull(value, "value");
            return this;
        }

        /** Replaces the built-in implementation for the pattern's mode. */
        public Builder pattern(AgentPattern value) {
            this.customPatterns.add(Objects.requireNonNull(value, "value"));
            return this;
        }

        public Builder maxReActIterations(int value) {
            this.maxReActIterations = Math.max(1, value);
            return this;
        }

        public Builder maxPlanRounds(int value) {
            this.maxPlanRounds = Math.max(1, value);
            return this;
        }

        public Builder maxStepRetries(int value) {
            this.maxStepRetries = Math.max(0, value);
            return this;
        }

        public DefaultAgentRuntime build() {
            return new DefaultAgentRuntime(this);
        }
    }

    private static final class RunState {
        private final String taskId;
        private final String parentTaskId;
        private final Instant createdAt;
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final AtomicBoolean executing = new AtomicBoolean();
        private final AtomicLong sequence = new AtomicLong();
        private final ConcurrentLinkedQueue<AgentInstruction> instructions =
                new ConcurrentLinkedQueue<>();
        private volatile AgentRequest request;
        private volatile AgentEventListener listener;
        private volatile AgentStatus status = AgentStatus.CREATED;
        private volatile Instant updatedAt;
        private volatile CompletableFuture<AgentResult> completion;
        private volatile String output;
        private volatile String error;
        private volatile String mode = AgentMode.DIRECT.name();
        private volatile long revision;

        private RunState(String taskId, String parentTaskId, AgentRequest request,
                         AgentEventListener listener) {
            this.taskId = taskId;
            this.parentTaskId = parentTaskId;
            this.request = request;
            this.listener = listener;
            this.createdAt = Instant.now();
            this.updatedAt = createdAt;
        }

        private RunState(AgentRunRecord record, AgentEventListener listener) {
            this.taskId = record.taskId();
            this.parentTaskId = record.parentTaskId();
            this.request = record.request();
            this.listener = listener;
            this.createdAt = record.createdAt();
            this.updatedAt = record.updatedAt();
            this.status = record.status();
            this.mode = record.mode();
            this.output = record.output();
            this.error = record.error();
            this.instructions.addAll(record.queuedInstructions());
            this.cancelled.set(record.cancellationRequested());
            this.revision = record.revision();
            this.sequence.set(record.revision());
        }
    }
}
