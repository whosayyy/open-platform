package com.yin.ai.platform.agent.pattern;

import com.yin.ai.platform.agent.AgentContext;
import com.yin.ai.platform.agent.event.AgentEvent;
import com.yin.ai.platform.agent.event.EventPublisher;
import com.yin.ai.platform.agent.policy.AgentStepLimitExceededException;
import com.yin.ai.platform.agent.task.ContextStore;
import com.yin.ai.platform.agent.task.TaskState;
import com.yin.ai.platform.agent.task.TaskEngine;
import com.yin.ai.platform.gateway.TokenUsage;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import com.yin.ai.platform.core.agent.mode.AgentMode;
import com.yin.ai.platform.core.agent.pattern.StepType;

/**
 * 统一执行上下文 — 所有 Agent Pattern 的共享状态载体。
 * <p>
 * 设计决策：
 * <ul>
 *   <li>不可变快照 + 可变工作区：snapshot() 返回不可变 record 用于序列化，
 *       运行时通过 putVariable / addArtifact 等方法修改工作区</li>
 *   <li>Redis 持久化：每次状态变更通过 ContextStore 异步写入 Redis</li>
 *   <li>与现有 AgentContext 的关系：包装 AgentContext，复用其消息历史、
 *       配置等能力，新增 taskId / sessionId / agentMode / artifacts / plan 等一等公民字段</li>
 * </ul>
 */
public class AgentExecutionContext {

    private static final String INITIAL_UI_STEP_IDS = "ui.plan.initialStepIds";

    // === 身份标识（不可变） ===
    private final String taskId;
    private final String sessionId;
    private final String userId;
    private final AgentMode agentMode;
    private final Instant createdAt;

    // === 执行状态（可变，持久化） ===
    private volatile TaskState state;
    private volatile String currentStepId;
    private volatile int stepIndex;
    private final ThreadLocal<StepScope> threadStepScope = new ThreadLocal<>();

    // === 工作区（可变，持久化） ===
    private final ConcurrentHashMap<String, Object> variables;
    private final CopyOnWriteArrayList<Artifact> artifacts;
    private final CopyOnWriteArrayList<StepRecord> stepRecords;
    private volatile TaskPlan plan;

    // === 运行时引用（不持久化） ===
    private final AgentContext agentContext;
    private volatile EventPublisher eventPublisher;
    private final ContextStore contextStore;
    private final StepArtifactStore stepArtifactStore;
    /**
     * 任务生命周期同步回调。AgentExecutionContext 管理模式内状态，
     * 由 Runtime 将宏观状态同步到 TaskEngine/Redis。
     */
    private final Consumer<TaskState> taskStateSynchronizer;
    private volatile TaskEngine taskEngine;

    // === 构造 ===

    public AgentExecutionContext(String taskId, String sessionId, String userId,
                                  AgentMode agentMode, AgentContext agentContext,
                                  EventPublisher eventPublisher, ContextStore contextStore) {
        this(taskId, sessionId, userId, agentMode, agentContext, eventPublisher, contextStore, null, null);
    }

    public AgentExecutionContext(String taskId, String sessionId, String userId,
                                  AgentMode agentMode, AgentContext agentContext,
                                  EventPublisher eventPublisher, ContextStore contextStore,
                                  Consumer<TaskState> taskStateSynchronizer) {
        this(taskId, sessionId, userId, agentMode, agentContext, eventPublisher, contextStore,
                taskStateSynchronizer, null);
    }

    public AgentExecutionContext(String taskId, String sessionId, String userId,
                                  AgentMode agentMode, AgentContext agentContext,
                                  EventPublisher eventPublisher, ContextStore contextStore,
                                  Consumer<TaskState> taskStateSynchronizer,
                                  StepArtifactStore stepArtifactStore) {
        this.taskId = taskId;
        this.sessionId = sessionId;
        this.userId = userId;
        this.agentMode = agentMode;
        this.createdAt = Instant.now();
        this.state = TaskState.CREATED;
        this.currentStepId = null;
        this.stepIndex = 0;
        this.variables = new ConcurrentHashMap<>();
        this.artifacts = new CopyOnWriteArrayList<>();
        this.stepRecords = new CopyOnWriteArrayList<>();
        this.plan = null;
        this.agentContext = agentContext;
        this.eventPublisher = eventPublisher;
        this.contextStore = contextStore;
        this.taskStateSynchronizer = taskStateSynchronizer;
        this.stepArtifactStore = stepArtifactStore;
    }

    /** 从快照恢复（多实例 / 中断恢复） */
    public static AgentExecutionContext restore(ContextSnapshot snap,
                                                 AgentContext agentCtx,
                                                 EventPublisher publisher,
                                                 ContextStore store) {
        return restore(snap, agentCtx, publisher, store, null, null);
    }

    /** 从快照恢复，并绑定任务生命周期同步器。 */
    public static AgentExecutionContext restore(ContextSnapshot snap,
                                                 AgentContext agentCtx,
                                                  EventPublisher publisher,
                                                  ContextStore store,
                                                  Consumer<TaskState> taskStateSynchronizer) {
        return restore(snap, agentCtx, publisher, store, taskStateSynchronizer, null);
    }

    public static AgentExecutionContext restore(ContextSnapshot snap,
                                                 AgentContext agentCtx,
                                                 EventPublisher publisher,
                                                 ContextStore store,
                                                 Consumer<TaskState> taskStateSynchronizer,
                                                 StepArtifactStore stepArtifactStore) {
        AgentExecutionContext ctx = new AgentExecutionContext(
                snap.taskId(), snap.sessionId(), snap.userId(),
                snap.agentMode(), agentCtx, publisher, store, taskStateSynchronizer, stepArtifactStore);
        ctx.state = snap.state();
        ctx.currentStepId = snap.currentStepId();
        ctx.stepIndex = snap.stepIndex();
        ctx.variables.putAll(snap.variables());
        ctx.artifacts.addAll(snap.artifacts());
        if (snap.stepRecords() != null) {
            ctx.stepRecords.addAll(snap.stepRecords());
        }
        ctx.plan = snap.plan();
        return ctx;
    }

    // === 变量操作 ===

    /** 写入变量并异步持久化 */
    public void putVariable(String key, Object value) {
        variables.put(key, value);
        contextStore.saveVariableAsync(taskId, key, value);
    }

    /** 读取变量 */
    @SuppressWarnings("unchecked")
    public <T> T getVariable(String key, Class<T> type) {
        Object val = variables.get(key);
        return val != null ? type.cast(val) : null;
    }

    /** 读取变量（无类型） */
    public Object getVariable(String key) {
        return variables.get(key);
    }

    public boolean hasVariable(String key) {
        return variables.containsKey(key);
    }

    /** Removes a transient value from subsequent full snapshots. */
    public void removeVariable(String key) {
        if (key != null) variables.remove(key);
    }

    /** Accumulates provider-reported usage across all LLM calls in this task. */
    public synchronized void accumulateTokenUsage(TokenUsage usage) {
        if (usage == null) return;
        addUsageCounter("usage.promptTokens", usage.getPromptTokens());
        addUsageCounter("usage.completionTokens", usage.getCompletionTokens());
        Integer total = usage.getTotalTokens();
        if (total == null && (usage.getPromptTokens() != null || usage.getCompletionTokens() != null)) {
            long derived = (long) Math.max(0, usage.getPromptTokens() != null ? usage.getPromptTokens() : 0)
                    + Math.max(0, usage.getCompletionTokens() != null ? usage.getCompletionTokens() : 0);
            total = derived > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) derived;
        }
        addUsageCounter("usage.totalTokens", total);
    }

    private void addUsageCounter(String key, Integer addition) {
        if (addition == null) return;
        Object currentValue = variables.get(key);
        long current = currentValue instanceof Number number ? number.longValue() : 0L;
        long updated = Math.min(Integer.MAX_VALUE, current + Math.max(0L, addition.longValue()));
        putVariable(key, (int) updated);
    }

    // === 产物操作 ===

    /** 追加产物并持久化 + 发射事件 */
    public void addArtifact(Artifact artifact) {
        artifacts.add(artifact);
        contextStore.saveArtifactAsync(taskId, artifact);
        eventPublisher.publish(AgentEvent.artifactUpdate(taskId, artifact));
    }

    public List<Artifact> getArtifacts() {
        return List.copyOf(artifacts);
    }

    /** Save a full worker output and expose only its reference to downstream steps. */
    public StepArtifactStore.ArtifactRef saveStepArtifact(String stepId, int index, String content) {
        if (stepArtifactStore == null || content == null || content.isBlank()) {
            return null;
        }
        try {
            StepArtifactStore.ArtifactRef ref = stepArtifactStore.save(taskId, stepId, index, content);
            if (ref != null) {
                if (ref.objectKey() != null) putVariable("artifact." + stepId + ".key", ref.objectKey());
                if (ref.url() != null) putVariable("artifact." + stepId + ".url", ref.url());
                if (ref.name() != null) putVariable("artifact." + stepId + ".name", ref.name());
            }
            return ref;
        } catch (RuntimeException ignored) {
            // Object storage must not make an otherwise recoverable LLM step fail.
            return null;
        }
    }

    public StepArtifactStore.Verification verifyStepArtifact(StepArtifactStore.ArtifactRef ref) {
        if (stepArtifactStore == null || ref == null) {
            return new StepArtifactStore.Verification(false, 0, null, "artifact_store_unavailable");
        }
        try {
            return stepArtifactStore.verify(ref);
        } catch (RuntimeException e) {
            return new StepArtifactStore.Verification(false, ref.size(), ref.sha256(), e.getMessage());
        }
    }

    // === 执行台账 ===

    /** 追加一条步骤执行记录（随 snapshot 持久化） */
    public void recordStep(StepRecord record) {
        if (record == null) return;
        stepRecords.add(record);
    }

    public void recordStepResult(StepResult result) {
        if (result == null) return;
        putVariable("step." + result.stepId() + ".result", result);
        if (taskEngine != null) taskEngine.saveStructuredStepResult(taskId, result);
    }

    public void recordStepSummary(StepSummary summary) {
        if (summary == null) return;
        putVariable("step." + summary.stepId() + ".summary", summary);
    }

    public boolean tryAcquireStepExecution(StepExecution execution, java.time.Duration ttl) {
        return taskEngine == null || taskEngine.tryAcquireStepExecution(taskId, execution.executionId(), ttl);
    }

    public void bindTaskEngine(TaskEngine taskEngine) {
        this.taskEngine = taskEngine;
    }

    public TaskEngine getTaskEngine() { return taskEngine; }

    public List<StepRecord> getStepRecords() {
        return List.copyOf(stepRecords);
    }

    // === 计划操作 ===

    /** 设置执行计划并持久化 + 发射事件 */
    public void setPlan(TaskPlan plan) {
        validatePlanLimit(plan);
        this.plan = plan;
        putVariable(INITIAL_UI_STEP_IDS, plan.steps().stream().map(TaskStep::id).toList());
        contextStore.savePlanAsync(taskId, plan);
        eventPublisher.publish(AgentEvent.planCreated(taskId, userVisiblePlan(plan)));
    }

    /** Replace an existing plan (replan/recovery) without pretending it is a new plan. */
    public void replacePlan(TaskPlan plan, boolean restored) {
        validatePlanLimit(plan);
        this.plan = plan;
        variables.computeIfAbsent(INITIAL_UI_STEP_IDS,
                ignored -> plan.steps().stream().map(TaskStep::id).toList());
        contextStore.savePlanAsync(taskId, plan);
        eventPublisher.publish(AgentEvent.planUpdated(taskId, userVisiblePlan(plan), restored));
    }

    private void validatePlanLimit(TaskPlan candidate) {
        if (candidate == null) return;
        Object configured = variables.get("agent.maxSteps");
        if (!(configured instanceof Number number)) return;
        int limit = number.intValue();
        if (limit > 0 && candidate.totalSteps() > limit) {
            throw new AgentStepLimitExceededException(candidate.totalSteps(), limit);
        }
    }

    /** 更新计划中的某个步骤 */
    public synchronized void updateStep(String stepId, TaskStep updatedStep) {
        if (plan == null) return;
        this.plan = plan.withUpdatedStep(stepId, updatedStep);
        contextStore.savePlanAsync(taskId, plan);
        // The runtime owns todo truth. LLM workers never need to call a separate
        // "update todo" tool after reporting step output.
        eventPublisher.publish(AgentEvent.planUpdated(taskId, userVisiblePlan(plan)));
    }

    /**
     * The runtime may append several executor-level search/readback operations
     * during reflection. They remain independently scheduled and audited, but
     * the workspace sees the original semantic plan plus one consolidated
     * online-verification stage instead of a growing list of tool calls.
     */
    private TaskPlan userVisiblePlan(TaskPlan runtimePlan) {
        Object storedIds = variables.get(INITIAL_UI_STEP_IDS);
        java.util.Set<String> initialIds = new java.util.LinkedHashSet<>();
        if (storedIds instanceof java.util.Collection<?> values) {
            values.forEach(value -> initialIds.add(String.valueOf(value)));
        }
        if (initialIds.isEmpty()) {
            runtimePlan.steps().forEach(step -> initialIds.add(step.id()));
        }

        java.util.List<TaskStep> visible = new java.util.ArrayList<>();
        java.util.List<TaskStep> dynamicSearches = new java.util.ArrayList<>();
        boolean initialSemanticSearchKept = false;
        for (TaskStep step : runtimePlan.steps()) {
            if (initialIds.contains(step.id())) {
                if (step.executor() != null && step.executor().startsWith("view_artifact")) {
                    continue;
                }
                if (isSearchExecutor(step.executor())) {
                    String description = step.description() == null ? ""
                            : step.description().trim().toLowerCase(java.util.Locale.ROOT);
                    boolean executorNamedStep = description.startsWith("web_search")
                            || description.startsWith("rag_search") || "fresh-search".equals(step.id());
                    if (executorNamedStep || initialSemanticSearchKept) {
                        dynamicSearches.add(step);
                    } else {
                        visible.add(step);
                        initialSemanticSearchKept = true;
                    }
                } else {
                    visible.add(step);
                }
            } else if (isSearchExecutor(step.executor())) {
                dynamicSearches.add(step);
            } else if (!isInternalToolExecutor(step.executor())) {
                visible.add(step);
            }
        }
        if (!dynamicSearches.isEmpty()) {
            StepStatus status = aggregateStatus(dynamicSearches);
            TaskStep verification = TaskStep.pending("online-verification", "联网核准资料",
                    "activity", List.of(), "集中核准新增事实，避免重复展示搜索调用")
                    .withStepType(StepType.SEARCH)
                    .withStatus(status);
            visible.add(verification);
        }
        return new TaskPlan(runtimePlan.goal(), visible);
    }

    private boolean isSearchExecutor(String executor) {
        if (executor == null) return false;
        String value = executor.toLowerCase(java.util.Locale.ROOT);
        return value.startsWith("web_search") || value.startsWith("rag_search");
    }

    private boolean isInternalToolExecutor(String executor) {
        if (executor == null || executor.isBlank() || "llm".equalsIgnoreCase(executor)) return false;
        return isSearchExecutor(executor) || executor.startsWith("view_artifact");
    }

    private StepStatus aggregateStatus(List<TaskStep> steps) {
        if (steps.stream().anyMatch(step -> step.status() == StepStatus.RUNNING)) return StepStatus.RUNNING;
        if (steps.stream().anyMatch(step -> step.status() == StepStatus.PENDING)) return StepStatus.PENDING;
        if (steps.stream().anyMatch(step -> step.status() == StepStatus.FAILED)) return StepStatus.FAILED;
        if (steps.stream().allMatch(step -> step.status() == StepStatus.COMPLETED)) return StepStatus.COMPLETED;
        return StepStatus.PENDING;
    }

    public TaskPlan getPlan() {
        return plan;
    }

    public TaskPlan getUserVisiblePlan() {
        return plan == null ? null : userVisiblePlan(plan);
    }

    // === 状态操作 ===

    public void transitionTo(TaskState newState) {
        Objects.requireNonNull(newState, "newState");
        if (!this.state.canTransitionTo(newState)) {
            throw new IllegalStateException(
                    "Illegal task state transition: " + this.state + " → " + newState);
        }
        if (taskStateSynchronizer != null) {
            taskStateSynchronizer.accept(newState);
        }
        this.state = newState;
    }

    public void setCurrentStep(String stepId, int index) {
        this.currentStepId = stepId;
        this.stepIndex = index;
    }

    /** Provides thread-local step identity while independent DAG nodes run concurrently. */
    public AutoCloseable openStepScope(String stepId, int index) {
        StepScope previous = threadStepScope.get();
        threadStepScope.set(new StepScope(stepId, index));
        return () -> {
            if (previous == null) threadStepScope.remove();
            else threadStepScope.set(previous);
        };
    }

    // === 快照 ===

    /** 生成不可变快照（用于 Redis 序列化 / 恢复） */
    public ContextSnapshot snapshot() {
        return new ContextSnapshot(
                taskId, sessionId, userId, agentMode,
                state, currentStepId, stepIndex,
                Map.copyOf(variables), List.copyOf(artifacts), List.copyOf(stepRecords),
                plan, createdAt, Instant.now()
        );
    }

    // === Getter ===

    public String getTaskId() { return taskId; }
    public String getSessionId() { return sessionId; }
    public String getUserId() { return userId; }
    public AgentMode getAgentMode() { return agentMode; }
    public TaskState getState() { return state; }
    public String getCurrentStepId() {
        StepScope scope = threadStepScope.get();
        return scope != null ? scope.stepId() : currentStepId;
    }
    public int getStepIndex() {
        StepScope scope = threadStepScope.get();
        return scope != null ? scope.index() : stepIndex;
    }
    public Instant getCreatedAt() { return createdAt; }
    public AgentContext getAgentContext() { return agentContext; }
    public EventPublisher getEventPublisher() { return eventPublisher; }
    public void setEventPublisher(EventPublisher eventPublisher) {
        if (eventPublisher != null) {
            this.eventPublisher = eventPublisher;
        }
    }
    public ContextStore getContextStore() { return contextStore; }

    /** 获取任务目标（从 AgentContext 的 task 中获取） */
    public String getGoal() {
        if (agentContext != null && agentContext.getTask() != null) {
            return agentContext.getTask().goal();
        }
        return getVariable("goal", String.class);
    }

    private record StepScope(String stepId, int index) {
    }
}
