package com.yin.ai.platform.agent.pattern;

import com.yin.ai.platform.agent.AgentContext;
import com.yin.ai.platform.agent.AgentConfig;
import com.yin.ai.platform.agent.AgentTask;
import com.yin.ai.platform.agent.event.AgentEvent;
import com.yin.ai.platform.agent.event.EventPublisher;
import com.yin.ai.platform.agent.memory.AgentMemoryLifecycle;
import com.yin.ai.platform.agent.observe.AgentRuntimeMetrics;
import com.yin.ai.platform.agent.policy.AgentStepPolicy;
import com.yin.ai.platform.agent.task.*;
import com.yin.ai.platform.engine.ExecutionContext;
import com.yin.ai.platform.engine.ExecutionContextHolder;
import com.yin.ai.platform.gateway.TokenUsage;
import com.yin.ai.platform.observe.RouterFirstMonitor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.Disposable;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import com.yin.ai.platform.core.agent.mode.AgentMode;

/**
 * 统一 Agent Runtime — 所有模式的唯一入口。
 * <p>
 * 替代现有 DefaultAgentRuntime 中仅支持 ReAct 的限制。
 * <p>
 * 职责：
 * 1. 接收任务 → 路由到 AgentMode
 * 2. 创建 TaskEngine 任务 + AgentExecutionContext
 * 3. 委托给对应 AgentPattern 执行
 * 4. 桥接事件流到 SSE（通过 EventPublisher）
 * 5. 管理任务生命周期（通过 TaskEngine）
 * <p>
 * 与现有 {@code com.yin.ai.platform.agent.runtime.AgentRuntime} 的关系：
 * - 旧接口保留（向后兼容），内部委托给本类
 * - 新代码直接使用本类
 */
public class UnifiedAgentRuntime {

    private static final Logger log = LoggerFactory.getLogger(UnifiedAgentRuntime.class);

    private final AgentRouter router;
    private final TaskEngine taskEngine;
    private final Map<AgentMode, AgentPattern> patterns;
    private final ContextStore contextStore;
    private final EventPublisher eventPublisher;
    private final StepArtifactStore stepArtifactStore;
    private final TaskTokenAccountant tokenAccountant;
    private final AgentMemoryLifecycle memoryLifecycle;
    private final AgentStepPolicy stepPolicy;
    private AgentPatternBridge patternBridge;
    private RouterFirstMonitor routerFirstMonitor;
    private AgentRuntimeMetrics runtimeMetrics;

    public UnifiedAgentRuntime(AgentRouter router,
                                TaskEngine taskEngine,
                                java.util.List<AgentPattern> patternList,
                                ContextStore contextStore,
                                EventPublisher eventPublisher) {
        this(router, taskEngine, patternList, contextStore, eventPublisher, null);
    }

    public UnifiedAgentRuntime(AgentRouter router,
                                TaskEngine taskEngine,
                                java.util.List<AgentPattern> patternList,
                                ContextStore contextStore,
                                EventPublisher eventPublisher,
                                StepArtifactStore stepArtifactStore) {
        this(router, taskEngine, patternList, contextStore, eventPublisher, stepArtifactStore, null);
    }

    public UnifiedAgentRuntime(AgentRouter router,
                                TaskEngine taskEngine,
                                java.util.List<AgentPattern> patternList,
                                ContextStore contextStore,
                                EventPublisher eventPublisher,
                                StepArtifactStore stepArtifactStore,
                                TaskTokenAccountant tokenAccountant) {
        this(router, taskEngine, patternList, contextStore, eventPublisher,
                stepArtifactStore, tokenAccountant, null);
    }

    public UnifiedAgentRuntime(AgentRouter router,
                                TaskEngine taskEngine,
                                java.util.List<AgentPattern> patternList,
                                ContextStore contextStore,
                                EventPublisher eventPublisher,
                                StepArtifactStore stepArtifactStore,
                                TaskTokenAccountant tokenAccountant,
                                AgentMemoryLifecycle memoryLifecycle) {
        this(router, taskEngine, patternList, contextStore, eventPublisher, stepArtifactStore,
                tokenAccountant, memoryLifecycle, null);
    }

    public UnifiedAgentRuntime(AgentRouter router,
                               TaskEngine taskEngine,
                               java.util.List<AgentPattern> patternList,
                               ContextStore contextStore,
                               EventPublisher eventPublisher,
                               StepArtifactStore stepArtifactStore,
                               TaskTokenAccountant tokenAccountant,
                               AgentMemoryLifecycle memoryLifecycle,
                               AgentStepPolicy stepPolicy) {
        this.router = router;
        this.taskEngine = taskEngine;
        this.patterns = patternList.stream()
                .collect(Collectors.toMap(AgentPattern::mode, Function.identity()));
        this.contextStore = contextStore;
        this.eventPublisher = eventPublisher;
        this.stepArtifactStore = stepArtifactStore;
        this.tokenAccountant = tokenAccountant;
        this.memoryLifecycle = memoryLifecycle;
        this.stepPolicy = stepPolicy;

        log.info("[RUNTIME] initialized with patterns: {}", patterns.keySet());
    }

    /**
     * 流式执行（主入口）。
     * <p>
     * 流程：路由 → 创建任务 → 构建上下文 → 委托 Pattern → 事件流
     */
    public Flux<AgentEvent> execute(AgentTaskRequest request) {
        // 1. 路由
        AgentMode mode = router.route(request.goal(), request.routeContext());
        if (routerFirstMonitor != null) routerFirstMonitor.recordAgent();
        log.info("[RUNTIME] routed goal='{}' → mode={}", truncate(request.goal(), 50), mode);

        // 2. 创建任务
        TaskHandle handle = taskEngine.create(new TaskCreateRequest(
                request.goal(), request.userId(), request.sessionId(), mode,
                request.metadata() != null ? request.metadata() : Map.of()));
        String taskId = handle.taskId();
        initializeBudget(taskId, request);

        // 3. 构建上下文
        AgentExecutionContext ctx = buildContext(handle, request);
        beginMemory(ctx);

        // 4. 发射 TASK_CREATED 事件
        AgentEvent taskCreated = AgentEvent.taskCreated(taskId, mode.name());
        eventPublisher.publish(taskCreated);

        // 5. 启动任务 + 委托 Pattern
        AgentPattern pattern = patterns.get(mode);
        if (pattern == null) {
            return Flux.error(new IllegalStateException("No pattern registered for mode: " + mode));
        }

        taskEngine.start(taskId);

        return Flux.just(taskCreated)
                .concatWith(inExecutionContext(ctx, () -> executePattern(pattern, ctx, false)))
                .subscribeOn(Schedulers.boundedElastic())
                .doOnComplete(() -> completeExecution(taskId, ctx))
                .doOnError(e -> {
                    log.error("[RUNTIME] task failed taskId={}", taskId, e);
                    failExecution(taskId, ctx);
                })
                .concatWith(Flux.defer(() -> finalEvent(taskId, ctx)));
    }

    /**
     * 任务提交结果 — 包含 taskId（立即可用）和事件流（异步执行）。
     */
    public record TaskSubmission(String taskId, AgentMode mode, Flux<AgentEvent> events) {}

    /**
     * Create and persist a task without starting its pattern.  The returned
     * stream contains only {@code task.created}; connecting an SSE consumer is
     * therefore never capable of starting execution.
     */
    public TaskSubmission prepare(AgentTaskRequest request) {
        AgentMode mode = router.route(request.goal(), request.routeContext());
        if (routerFirstMonitor != null) routerFirstMonitor.recordAgent();
        TaskHandle handle = taskEngine.create(new TaskCreateRequest(
                request.goal(), request.userId(), request.sessionId(), mode,
                request.metadata() != null ? request.metadata() : Map.of()));
        String taskId = handle.taskId();
        initializeBudget(taskId, request);

        AgentExecutionContext ctx = buildContext(handle, request);
        beginMemory(ctx);
        contextStore.saveSnapshot(taskId, ctx.snapshot());

        AgentEvent taskCreated = AgentEvent.taskCreated(taskId, mode.name());
        eventPublisher.publish(taskCreated);
        if (!patterns.containsKey(mode)) {
            throw new IllegalStateException("No pattern registered for mode: " + mode);
        }
        log.info("[RUNTIME] prepared taskId={} mode={}", taskId, mode);
        return new TaskSubmission(taskId, mode, Flux.just(taskCreated));
    }

    /**
     * 提交任务（异步） — 立即返回 taskId，Pattern 在后台执行。
     * <p>
     * 与 {@link #execute(AgentTaskRequest)} 的区别：
     * - execute() 返回纯 Flux，taskId 嵌入事件中，调用方需订阅后才能获取
     * - submit() 立即返回 taskId（供 Service 层初始化 SSE Sink / 注册并发限制），
     *   事件流通过 {@link TaskSubmission#events()} 获取
     */
    public TaskSubmission submit(AgentTaskRequest request) {
        // 1. 路由
        AgentMode mode = router.route(request.goal(), request.routeContext());
        if (routerFirstMonitor != null) routerFirstMonitor.recordAgent();
        log.info("[RUNTIME] submit routed goal='{}' → mode={}", truncate(request.goal(), 50), mode);

        // 2. 创建任务
        TaskHandle handle = taskEngine.create(new TaskCreateRequest(
                request.goal(), request.userId(), request.sessionId(), mode,
                request.metadata() != null ? request.metadata() : Map.of()));
        String taskId = handle.taskId();
        initializeBudget(taskId, request);

        // 3. 构建上下文
        AgentExecutionContext ctx = buildContext(handle, request);
        beginMemory(ctx);

        // 4. 发射 TASK_CREATED 事件
        AgentEvent taskCreated = AgentEvent.taskCreated(taskId, mode.name());
        eventPublisher.publish(taskCreated);

        // 5. 启动任务 + 委托 Pattern
        AgentPattern pattern = patterns.get(mode);
        if (pattern == null) {
            throw new IllegalStateException("No pattern registered for mode: " + mode);
        }

        taskEngine.start(taskId);

        Flux<AgentEvent> events = Flux.just(taskCreated)
                .concatWith(inExecutionContext(ctx, () -> executePattern(pattern, ctx, false)))
                .subscribeOn(Schedulers.boundedElastic())
                .doOnComplete(() -> completeExecution(taskId, ctx))
                .doOnError(e -> {
                    log.error("[RUNTIME] task failed taskId={}", taskId, e);
                    failExecution(taskId, ctx);
                })
                .concatWith(Flux.defer(() -> finalEvent(taskId, ctx)));

        return new TaskSubmission(taskId, mode, events);
    }

    /**
     * 恢复中断的任务。
     */
    public Flux<AgentEvent> resume(String taskId) {
        return resume(taskId, Map.of());
    }

    public boolean canResume(String taskId) {
        TaskSnapshot snap = taskEngine.getSnapshot(taskId);
        return snap != null
                && patterns.containsKey(snap.mode())
                && ((snap.state() == TaskState.CREATED)
                || (snap.mode().resumable() && (snap.state() == TaskState.FAILED
                || snap.state() == TaskState.RUNNING
                || snap.state() == TaskState.WAITING)))
                && contextStore.loadSnapshot(taskId) != null;
    }

    public Flux<AgentEvent> resume(String taskId, Map<String, String> answers) {
        TaskSnapshot snap = taskEngine.getSnapshot(taskId);
        if (snap == null) {
            return Flux.error(new IllegalArgumentException("Task not found: " + taskId));
        }

        boolean prepared = snap.state() == TaskState.CREATED;
        AgentPattern pattern = patterns.get(snap.mode());
        if (pattern == null || (!prepared && !pattern.resumable())) {
            return Flux.error(new IllegalStateException(
                    "Task " + taskId + " (mode=" + snap.mode() + ") is not resumable"));
        }

        // 从 Redis 恢复完整快照；RedisContextStore.restore() 仅保留兼容接口，实际由 Runtime 组装上下文。
        ContextSnapshot contextSnapshot = contextStore.loadSnapshot(taskId);
        if (contextSnapshot == null) {
            return Flux.error(new IllegalStateException("Cannot restore context for task: " + taskId));
        }
        AgentExecutionContext ctx = AgentExecutionContext.restore(
                contextSnapshot, buildAgentContext(contextSnapshot, snap.goal()), eventPublisher, contextStore,
                state -> synchronizeTaskState(taskId, state), stepArtifactStore);
        ctx.bindTaskEngine(taskEngine);
        if (!prepared) {
            ctx.putVariable("resume", Boolean.TRUE);
        }
        if (answers != null) {
            answers.forEach(ctx::putVariable);
        }

        if (prepared) {
            taskEngine.start(taskId);
        } else if (!taskEngine.tryResume(taskId)) {
            return Flux.error(new IllegalStateException("Task cannot be resumed: " + taskId));
        }
        AgentEvent lifecycleEvent = new AgentEvent(null, taskId,
                prepared
                        ? com.yin.ai.platform.agent.event.TaskEventType.TASK_RUNNING
                        : com.yin.ai.platform.agent.event.TaskEventType.TASK_RESUMED,
                null, Map.of(), java.time.Instant.now());
        // DefaultTaskEngine.tryResume already persisted the canonical resumed event.
        // The Flux copy below is only the live bridge projection.
        Flux<AgentEvent> resumedExecution = inExecutionContext(ctx, () -> {
            Supplier<Flux<AgentEvent>> legacyExecution = () ->
                    pattern instanceof DeepResearchAgentPattern deepResearch
                            && "clarification_waiting".equals(ctx.getVariable("phase"))
                            ? deepResearch.resumeAfterClarification(
                                    ctx, answers != null ? answers : Map.of())
                            : pattern.execute(ctx);
            if (prepared || (patternBridge != null
                    && patternBridge.supports(pattern.mode(), ctx, true))) {
                return executePattern(pattern, ctx, !prepared);
            }
            return legacyExecution.get();
        });

        return Flux.just(lifecycleEvent)
                .concatWith(resumedExecution)
                .subscribeOn(Schedulers.boundedElastic())
                .doOnComplete(() -> completeExecution(taskId, ctx))
                .doOnError(e -> failExecution(taskId, ctx))
                .concatWith(Flux.defer(() -> finalEvent(taskId, ctx)));
    }

    /**
     * 取消任务。
     */
    public void cancel(String taskId, String reason) {
        taskEngine.cancelTree(taskId, reason);
    }

    public TaskSnapshot getSnapshot(String taskId) {
        return taskEngine.getSnapshot(taskId);
    }

    public List<TaskSnapshot> listChildren(String parentTaskId) {
        return taskEngine.listChildren(parentTaskId);
    }

    public ContextSnapshot getContextSnapshot(String taskId) {
        return contextStore.loadSnapshot(taskId);
    }

    /** Returns task-level usage collected by the shared runtime accountant. */
    public TokenUsage getTokenUsage(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return new TokenUsage();
        }
        ContextSnapshot snapshot = contextStore.loadSnapshot(taskId);
        Integer prompt = usageValue(snapshot, "usage.promptTokens");
        Integer completion = usageValue(snapshot, "usage.completionTokens");
        Integer contextualTotal = usageValue(snapshot, "usage.totalTokens");
        long accountedTotal = tokenAccountant != null
                ? Math.max(0L, tokenAccountant.getSummary(taskId).totalTokens()) : 0L;
        long total = contextualTotal != null ? contextualTotal.longValue() : accountedTotal;
        if (total == 0L && (prompt != null || completion != null)) {
            total = (long) (prompt != null ? prompt : 0) + (completion != null ? completion : 0);
        }
        Integer persistedTotal = total > 0L
                ? (total > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) total) : null;
        return new TokenUsage(prompt, completion, persistedTotal);
    }

    private Integer usageValue(ContextSnapshot snapshot, String key) {
        if (snapshot == null || snapshot.variables() == null) return null;
        Object value = snapshot.variables().get(key);
        if (!(value instanceof Number number)) return null;
        long safe = Math.max(0L, number.longValue());
        return safe > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) safe;
    }

    public boolean queueInstruction(String taskId, String instruction) {
        TaskSnapshot snapshot = taskEngine.getSnapshot(taskId);
        if (snapshot == null || snapshot.state().isTerminal()
                || instruction == null || instruction.isBlank()) return false;
        taskEngine.enqueueInstruction(taskId, instruction);
        return true;
    }

    public void setRouterFirstMonitor(RouterFirstMonitor routerFirstMonitor) {
        this.routerFirstMonitor = routerFirstMonitor;
    }

    public void setRuntimeMetrics(AgentRuntimeMetrics runtimeMetrics) {
        this.runtimeMetrics = runtimeMetrics;
    }

    /** Installs the optional adapter that delegates fresh executions to core. */
    public void setPatternBridge(AgentPatternBridge patternBridge) {
        this.patternBridge = patternBridge;
    }

    // === internal ===

    private Flux<AgentEvent> executePattern(AgentPattern legacyPattern,
                                            AgentExecutionContext context,
                                            boolean resuming) {
        if (patternBridge != null
                && patternBridge.supports(legacyPattern.mode(), context, resuming)) {
            log.info("[RUNTIME] delegating taskId={} mode={} to core pattern",
                    context.getTaskId(), legacyPattern.mode());
            return patternBridge.execute(legacyPattern.mode(), context);
        }
        return legacyPattern.execute(context);
    }

    /**
     * Reactor may subscribe to an Agent pattern on a worker thread after the HTTP
     * thread has returned. Rebind the immutable request context for the complete
     * pattern subscription so Gateway, Memory and reflection calls retain tenant,
     * authenticated user and trace identity.
     */
    private Flux<AgentEvent> inExecutionContext(
            AgentExecutionContext context, Supplier<Flux<AgentEvent>> source) {
        ExecutionContext executionContext = context.getAgentContext() != null
                ? context.getAgentContext().getExecCtx() : null;
        AtomicReference<Flux<AgentEvent>> preparedSource = new AtomicReference<>();
        Runnable prepare = () -> preparedSource.set(source.get());
        if (executionContext == null) {
            prepare.run();
        } else {
            ExecutionContextHolder.runWith(executionContext, prepare);
        }

        return Flux.create(sink -> {
            AtomicReference<Disposable> innerSubscription = new AtomicReference<>();
            sink.onCancel(() -> {
                Disposable disposable = innerSubscription.get();
                if (disposable != null) disposable.dispose();
            });
            Runnable subscribe = () -> {
                try {
                    Disposable disposable = preparedSource.get()
                            .subscribe(sink::next, sink::error, sink::complete);
                    innerSubscription.set(disposable);
                    if (sink.isCancelled()) disposable.dispose();
                } catch (RuntimeException error) {
                    sink.error(error);
                }
            };
            if (executionContext == null) {
                subscribe.run();
            } else {
                ExecutionContextHolder.runWith(executionContext, subscribe);
            }
        });
    }

    private void initializeBudget(String taskId, AgentTaskRequest request) {
        if (tokenAccountant == null) return;
        Map<String, String> metadata = request.metadata() != null ? request.metadata() : Map.of();
        TaskTokenBudget defaults = TaskTokenBudget.DEFAULT;
        TaskTokenBudget budget = new TaskTokenBudget(
                parseLong(metadata.get("maxTotalTokens"), defaults.maxTotalTokens()),
                parseLong(metadata.get("maxTokensPerStep"), defaults.maxTokensPerStep()),
                (int) parseLong(metadata.get("maxLlmCallsPerStep"), defaults.maxLlmCallsPerStep()),
                (int) parseLong(metadata.get("tokenWarningPercent"), defaults.warningThresholdPercent()));
        tokenAccountant.setBudget(taskId, budget);
    }

    private long parseLong(String value, long fallback) {
        try {
            return value == null ? fallback : Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private void completeExecution(String taskId, AgentExecutionContext ctx) {
        if (ctx.getState() == TaskState.WAITING) {
            ensureTaskState(taskId, TaskState.WAITING);
        } else {
            // 兼容模式实现未显式推进上下文状态的情况；完成前必须经过 RUNNING。
            if (ctx.getState() == TaskState.PLANNING) {
                ctx.transitionTo(TaskState.RUNNING);
            }
            if (ctx.getState() == TaskState.RUNNING) {
                ctx.transitionTo(TaskState.COMPLETED);
            }
            ensureTaskState(taskId, TaskState.COMPLETED);
        }
        contextStore.saveSnapshot(taskId, ctx.snapshot());
        if (memoryLifecycle != null) memoryLifecycle.complete(ctx);
        if (runtimeMetrics != null) {
            runtimeMetrics.record(ctx, ctx.getState() == TaskState.WAITING ? "waiting" : "completed");
        }
    }

    private void failExecution(String taskId, AgentExecutionContext ctx) {
        if (ctx.getState() == TaskState.RUNNING || ctx.getState() == TaskState.PLANNING) {
            ctx.transitionTo(TaskState.FAILED);
        }
        ensureTaskState(taskId, TaskState.FAILED);
        contextStore.saveSnapshot(taskId, ctx.snapshot());
        if (memoryLifecycle != null) memoryLifecycle.fail(ctx);
        if (runtimeMetrics != null) runtimeMetrics.record(ctx, "failed");
    }

    private void beginMemory(AgentExecutionContext ctx) {
        if (memoryLifecycle == null) return;
        try {
            memoryLifecycle.begin(ctx);
        } catch (RuntimeException e) {
            log.warn("[RUNTIME] memory lifecycle initialization failed taskId={}: {}",
                    ctx.getTaskId(), e.getMessage());
        }
    }

    /**
     * 将模式内上下文状态同步到 TaskEngine。PlanExecute/DeepResearch 在规划完成后
     * 先更新 AgentExecutionContext；如果不在这里同步，Redis 中的任务会一直停留在
     * PLANNING，最终完成时就会出现 PLANNING → COMPLETED 的非法转换。
     */
    private void synchronizeTaskState(String taskId, TaskState target) {
        TaskSnapshot snapshot = taskEngine.getSnapshot(taskId);
        if (snapshot == null || snapshot.state() == target) {
            return;
        }

        TaskState current = snapshot.state();
        if (target == TaskState.COMPLETED && current == TaskState.PLANNING) {
            taskEngine.transitionTo(taskId, TaskState.RUNNING);
            current = TaskState.RUNNING;
        }
        if (!current.canTransitionTo(target)) {
            throw new IllegalStateException(
                    "Illegal task state transition for " + taskId + ": " + current + " → " + target);
        }
        taskEngine.transitionTo(taskId, target);
    }

    /** 确保 Runtime 收口时 TaskEngine 已到达目标状态，兼容旧 Pattern。 */
    private void ensureTaskState(String taskId, TaskState target) {
        synchronizeTaskState(taskId, target);
    }

    private Flux<AgentEvent> finalEvent(String taskId, AgentExecutionContext ctx) {
        if (ctx.getState() == TaskState.WAITING) {
            return Flux.empty();
        }
        AgentEvent completed = AgentEvent.taskCompleted(taskId, resolveFinalOutput(ctx),
                stringVariable(ctx, "finalArtifactKey"),
                stringVariable(ctx, "finalArtifactUrl"),
                stringVariable(ctx, "finalArtifactName"));
        eventPublisher.publish(completed);
        return Flux.just(completed);
    }

    private String stringVariable(AgentExecutionContext ctx, String key) {
        Object value = ctx.getVariable(key);
        return value == null ? null : value.toString();
    }

    private AgentContext buildAgentContext(ContextSnapshot snapshot, String fallbackGoal) {
        Map<String, Object> taskContext = new java.util.HashMap<>();
        if (snapshot.variables() != null) {
            taskContext.putAll(snapshot.variables());
        }
        if (snapshot.sessionId() != null) {
            taskContext.put("sessionId", snapshot.sessionId());
        }
        if (snapshot.userId() != null) {
            taskContext.put("userId", snapshot.userId());
        }
        String goal = String.valueOf(taskContext.getOrDefault("goal", fallbackGoal != null ? fallbackGoal : ""));
        int maxSteps = numberValue(taskContext.get("agent.maxSteps"), AgentConfig.DEFAULT_MAX_STEPS);
        AgentTask task = new AgentTask(goal, taskContext, null, AgentConfig.of(maxSteps, null));
        return AgentContext.from(task);
    }

    private AgentExecutionContext buildContext(TaskHandle handle, AgentTaskRequest request) {
        // 构建 AgentContext（复用现有逻辑）
        int maxSteps = resolveMaxSteps(request);
        AgentContext agentCtx = buildAgentContext(request, maxSteps);

        AgentExecutionContext ctx = new AgentExecutionContext(
                handle.taskId(), request.sessionId(), request.userId(),
                handle.mode(), agentCtx, eventPublisher, contextStore,
                state -> synchronizeTaskState(handle.taskId(), state), stepArtifactStore);
        ctx.bindTaskEngine(taskEngine);

        // 将 goal 写入变量（供 Pattern 使用）
        ctx.putVariable("goal", request.goal());

        // 将 Agent 专属配置写入变量（供 Pattern 构建 LLM 请求时读取）
        if (request.systemPrompt() != null) {
            ctx.putVariable("systemPrompt", request.systemPrompt());
        }
        if (request.temperature() != null) {
            ctx.putVariable("temperature", request.temperature());
        }
        if (request.model() != null) {
            ctx.putVariable("model", request.model());
        }
        if (request.metadata() != null) {
            request.metadata().forEach(ctx::putVariable);
        }
        ctx.putVariable("agent.maxSteps", maxSteps);
        return ctx;
    }

    private AgentContext buildAgentContext(AgentTaskRequest request, int maxSteps) {
        // 复用现有 AgentTask.of() 工厂方法（自动填充默认 config）
        Map<String, Object> taskContext = new java.util.HashMap<>();
        if (request.metadata() != null) {
            taskContext.putAll(request.metadata());
        }
        if (request.sessionId() != null) {
            taskContext.put("sessionId", request.sessionId());
        }
        if (request.userId() != null) {
            taskContext.put("userId", request.userId());
        }
        AgentTask task = new AgentTask(request.goal(), taskContext, null, AgentConfig.of(maxSteps, null));
        return AgentContext.from(task);
    }

    private int resolveMaxSteps(AgentTaskRequest request) {
        if (stepPolicy != null) {
            return stepPolicy.resolve(request.metadata()).effectiveLimit();
        }
        Map<String, String> metadata = request.metadata() != null ? request.metadata() : Map.of();
        int requested = numberValue(metadata.get(AgentStepPolicy.REQUEST_KEY), AgentConfig.DEFAULT_MAX_STEPS);
        return Math.min(requested, AgentConfig.PLATFORM_MAX_STEPS);
    }

    private int numberValue(Object value, int fallback) {
        try {
            int parsed = value == null ? fallback : Integer.parseInt(value.toString());
            return parsed > 0 ? parsed : fallback;
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private String resolveFinalOutput(AgentExecutionContext ctx) {
        Object explicit = ctx.getVariable("finalOutput");
        if (explicit != null && !explicit.toString().isBlank()) {
            return explicit.toString();
        }
        List<Artifact> artifacts = ctx.getArtifacts();
        if (!artifacts.isEmpty()) {
            Artifact last = artifacts.get(artifacts.size() - 1);
            if (last.content() != null && !last.content().isBlank()) {
                return last.content();
            }
        }
        Object response = ctx.getVariable("response");
        return response != null ? response.toString() : "";
    }

    private static String truncate(String s, int maxLen) {
        return s != null && s.length() > maxLen ? s.substring(0, maxLen) + "..." : s;
    }
}
