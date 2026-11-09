package com.yin.ai.platform.agent;

import com.yin.ai.platform.engine.ExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Agent 执行上下文 — ReAct Loop 期间的运行时状态容器。
 * <p>
 * 可变字段（messageHistory, variables, trace, state, currentStep, retryCount, startedAt）
 * 均保证线程安全。immutable 字段（agentId, execCtx, config, depth, createdAt）为 final。
 * <p>
 * 不实现 AutoCloseable — 生命周期由 DefaultAgentRuntime 管理。
 */
public class AgentContext {

    private static final Logger log = LoggerFactory.getLogger(AgentContext.class);

    // ——— Immutable fields ———
    private final String agentId;
    private final ExecutionContext execCtx;
    private final AgentConfig config;
    private final AgentTask task;
    private final int depth;
    private final Instant createdAt;
    private final String sessionId;
    /** 外部 taskId（来自 TaskManager），用于 cancel 时按 taskId 查找 Agent */
    private volatile String externalTaskId;

    // ——— Mutable state (thread-safe) ———
    private final List<String> messageHistory;
    private final Map<String, Object> variables;
    private final List<AgentEvent> trace;
    private final AtomicReference<AgentState> state;
    private volatile int currentStep;
    private volatile int retryCount;
    private volatile Instant startedAt;

    public AgentContext(ExecutionContext execCtx, AgentTask task, int depth) {
        this.agentId = UUID.randomUUID().toString();
        this.execCtx = execCtx;
        this.config = task.config();
        this.task = task;
        this.depth = depth;
        this.createdAt = Instant.now();
        this.messageHistory = Collections.synchronizedList(new ArrayList<>());
        this.variables = new ConcurrentHashMap<>();
        this.trace = Collections.synchronizedList(new ArrayList<>());
        this.state = new AtomicReference<>(AgentState.IDLE);
        this.currentStep = 0;
        this.retryCount = 0;

        if (task.context() != null) {
            this.variables.putAll(task.context());
            this.sessionId = (String) task.context().get("sessionId");
            this.externalTaskId = (String) task.context().get("taskId");
            Object resumeMessages = task.context().get("resumeMessages");
            Object resumeStep = task.context().get("resumeCheckpointStep");
            if (resumeStep != null) {
                this.messageHistory.add("system:Task resumed from checkpoint after step " + resumeStep
                        + "; reuse completed observations and continue from the latest progress.");
            }
            if (resumeMessages instanceof Iterable<?> messages) {
                for (Object message : messages) {
                    if (message != null && !message.toString().isBlank()) {
                        this.messageHistory.add(message.toString());
                    }
                }
            }
        } else {
            this.sessionId = null;
            this.externalTaskId = null;
        }
    }

    public static AgentContext from(AgentTask task) {
        return new AgentContext(ExecutionContext.fromCurrentRequest(), task, 0);
    }

    public static AgentContext from(AgentTask task, ExecutionContext execCtx) {
        return new AgentContext(execCtx, task, 0);
    }

    // ——— Accessors ———

    public String getAgentId() { return agentId; }
    public ExecutionContext getExecCtx() { return execCtx; }
    public AgentConfig getConfig() { return config; }
    public AgentTask getTask() { return task; }
    public int getDepth() { return depth; }
    public Instant getCreatedAt() { return createdAt; }
    public int getCurrentStep() { return currentStep; }
    public int getRetryCount() { return retryCount; }
    public Instant getStartedAt() { return startedAt; }

    // ——— State transitions ———

    public boolean transitionTo(AgentState target) {
        AgentState current = state.get();
        if (current == target) {
            return true;
        }
        if (!current.canTransitionTo(target)) {
            log.warn("[AGENT] agentId={} illegal transition {} → {}", agentId, current, target);
            return false;
        }
        boolean ok = state.compareAndSet(current, target);
        if (ok) {
            log.debug("[AGENT] agentId={} {} → {}", agentId, current, target);
        }
        return ok;
    }

    public boolean forceTransitionTo(AgentState target) {
        // terminal 状态不能被强制进入
        if (!target.isTerminal()) {
            return false;
        }
        AgentState current = state.get();
        // terminal 状态不能被重复进入
        if (current.isTerminal()) {
            return false;
        }
        boolean ok = state.compareAndSet(current, target);
        if (ok) {
            log.debug("[AGENT] agentId={} {} → {} (forced)", agentId, current, target);
        }
        return ok;
    }

    public String getSessionId() { return sessionId; }

    public String getExternalTaskId() { return externalTaskId; }
    public void setExternalTaskId(String externalTaskId) { this.externalTaskId = externalTaskId; }

    public AgentState getState() {
        return state.get();
    }

    public boolean isTerminal() {
        return state.get().isTerminal();
    }

    // ——— Message history ———

    public void addMessage(String role, String content) {
        messageHistory.add(role + ":" + content);
    }

    public List<String> getMessages() {
        synchronized (messageHistory) {
            return new ArrayList<>(messageHistory);
        }
    }

    public int messageCount() {
        return messageHistory.size();
    }

    // ——— Variables ———

    public void putVariable(String key, Object value) {
        variables.put(key, value);
    }

    /** 删除运行时变量，适用于恢复标记等一次性上下文。 */
    public void removeVariable(String key) {
        if (key != null) {
            variables.remove(key);
        }
    }

    public Object getVariable(String key) {
        return variables.get(key);
    }

    public <T> T getVariable(String key, Class<T> type) {
        Object value = variables.get(key);
        if (value != null && type.isInstance(value)) {
            return type.cast(value);
        }
        return null;
    }

    // ——— Trace ———

    public void recordEvent(AgentEvent event) {
        trace.add(event);
    }

    public List<AgentEvent> getTrace() {
        synchronized (trace) {
            return new ArrayList<>(trace);
        }
    }

    // ——— Step tracking ———

    public int incrementStep() {
        return ++currentStep;
    }

    /** 从持久化 checkpoint 恢复已完成步骤数。 */
    public void restoreCurrentStep(int completedSteps) {
        currentStep = Math.max(0, completedSteps);
    }

    public void markStarted() {
        if (this.startedAt == null) {
            this.startedAt = Instant.now();
        }
    }

    public Duration elapsed() {
        if (startedAt == null) {
            return Duration.ZERO;
        }
        return Duration.between(startedAt, Instant.now());
    }

    public boolean isTimedOut() {
        return startedAt != null && elapsed().compareTo(config.timeout()) > 0;
    }

    public boolean hasExceededMaxSteps() {
        return currentStep >= config.maxSteps();
    }

    // ——— Retry ———

    public int incrementRetryCount() {
        return ++retryCount;
    }

    public void resetRetryCount() {
        retryCount = 0;
    }

    public boolean hasExceededRetries() {
        return retryCount >= config.maxRetriesPerStep();
    }
}
