package com.yin.ai.platform.workflow.model;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * 任务运行实例 — 事实状态，由 WorkflowTaskStore 持久化，Redis 只作为快照加速。
 */
public class TaskRun {

    private String taskRunId;
    private String conversationId;
    private Long userId;
    private String workflowName;
    private String workflowVersion;
    private TaskStatus status = TaskStatus.CREATED;
    private String currentStepId;
    private boolean explicitPublishRequested;
    private boolean stageByStageMode;
    private int retryCount;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant completedAt;
    private String traceId;
    private String goal;
    /** WAITING_INPUT 时的暂停原因 */
    private String waitingInputReason;
    private Map<String, Object> context = new HashMap<>();

    public TaskRun() {
    }

    public boolean isTerminal() {
        return status != null && status.isTerminal();
    }

    public void touch() {
        this.updatedAt = Instant.now();
    }

    public String getTaskRunId() {
        return taskRunId;
    }

    public void setTaskRunId(String taskRunId) {
        this.taskRunId = taskRunId;
    }

    public String getConversationId() {
        return conversationId;
    }

    public void setConversationId(String conversationId) {
        this.conversationId = conversationId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getWorkflowName() {
        return workflowName;
    }

    public void setWorkflowName(String workflowName) {
        this.workflowName = workflowName;
    }

    public String getWorkflowVersion() {
        return workflowVersion;
    }

    public void setWorkflowVersion(String workflowVersion) {
        this.workflowVersion = workflowVersion;
    }

    public TaskStatus getStatus() {
        return status;
    }

    public void setStatus(TaskStatus status) {
        this.status = status;
    }

    public String getCurrentStepId() {
        return currentStepId;
    }

    public void setCurrentStepId(String currentStepId) {
        this.currentStepId = currentStepId;
    }

    public boolean isExplicitPublishRequested() {
        return explicitPublishRequested;
    }

    public void setExplicitPublishRequested(boolean explicitPublishRequested) {
        this.explicitPublishRequested = explicitPublishRequested;
    }

    public boolean isStageByStageMode() {
        return stageByStageMode;
    }

    public void setStageByStageMode(boolean stageByStageMode) {
        this.stageByStageMode = stageByStageMode;
    }

    public int getRetryCount() {
        return retryCount;
    }

    public void setRetryCount(int retryCount) {
        this.retryCount = retryCount;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(Instant completedAt) {
        this.completedAt = completedAt;
    }

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }

    public String getGoal() {
        return goal;
    }

    public void setGoal(String goal) {
        this.goal = goal;
    }

    public String getWaitingInputReason() {
        return waitingInputReason;
    }

    public void setWaitingInputReason(String waitingInputReason) {
        this.waitingInputReason = waitingInputReason;
    }

    public Map<String, Object> getContext() {
        return context;
    }

    public void setContext(Map<String, Object> context) {
        this.context = context == null ? new HashMap<>() : context;
    }
}
