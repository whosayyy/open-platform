package com.yin.ai.platform.engine;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicReference;

/**
 * AI 会话任务实体。
 * 绑定 TraceId、超时定时器引用与完成信号。
 * Sink 管道由 StreamPipelineManager 独立管理。
 */
public class Task {
    private final String taskId;
    private final String traceId;
    private final AtomicReference<TaskState> state;
    private final long createdAt;
    /** 最后活跃时间（Agent 每产生事件即更新），用于心跳扫描判断是否为孤儿任务 */
    private volatile long lastActiveAt;
    private final CompletableFuture<Void> completionFuture;

    private volatile ScheduledFuture<?> globalTimeoutFuture;
    private volatile ScheduledFuture<?> firstTokenTimeoutFuture;
    private volatile ScheduledFuture<?> toolTimeoutFuture;

    public Task(String taskId, String traceId) {
        this.taskId = taskId;
        this.traceId = traceId;
        this.state = new AtomicReference<>(TaskState.CREATED);
        this.createdAt = System.currentTimeMillis();
        this.lastActiveAt = this.createdAt;
        this.completionFuture = new CompletableFuture<>();
    }

    public String getTaskId() { return taskId; }
    public String getTraceId() { return traceId; }
    public TaskState getState() { return state.get(); }
    public long getCreatedAt() { return createdAt; }
    public long getLastActiveAt() { return lastActiveAt; }
    public void touch() { this.lastActiveAt = System.currentTimeMillis(); }
    public CompletableFuture<Void> getCompletionFuture() { return completionFuture; }

    public ScheduledFuture<?> getGlobalTimeoutFuture() { return globalTimeoutFuture; }
    public void setGlobalTimeoutFuture(ScheduledFuture<?> f) { this.globalTimeoutFuture = f; }
    public ScheduledFuture<?> getFirstTokenTimeoutFuture() { return firstTokenTimeoutFuture; }
    public void setFirstTokenTimeoutFuture(ScheduledFuture<?> f) { this.firstTokenTimeoutFuture = f; }
    public ScheduledFuture<?> getToolTimeoutFuture() { return toolTimeoutFuture; }
    public void setToolTimeoutFuture(ScheduledFuture<?> f) { this.toolTimeoutFuture = f; }

    public boolean transitionTo(TaskState target) {
        TaskState current = state.get();
        if (current.isTerminal()) return false;
        return state.compareAndSet(current, target);
    }

    public boolean forceTransitionTo(TaskState target) {
        if (!target.isTerminal()) return false;
        TaskState current = state.get();
        if (current.isTerminal()) return false;
        return state.compareAndSet(current, target);
    }

    public boolean isTerminal() {
        return state.get().isTerminal();
    }
}
