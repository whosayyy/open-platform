package com.yin.ai.platform.workflow.model;

import java.time.Instant;

/**
 * 步骤运行实例 — 记录状态、尝试次数与错误信息，由 Runtime 维护。
 */
public class StepRun {

    private String stepRunId;
    private String taskRunId;
    private String stepId;
    private String stepName;
    private StepStatus status = StepStatus.PENDING;
    private int attempt;
    private Instant startedAt;
    private Instant completedAt;
    private String errorCode;
    private String errorMessage;
    private String outputArtifactId;
    private Long durationMs;

    public StepRun() {
    }

    public static StepRun of(String taskRunId, StepDefinition definition) {
        StepRun run = new StepRun();
        run.setStepRunId(java.util.UUID.randomUUID().toString());
        run.setTaskRunId(taskRunId);
        run.setStepId(definition.getId());
        run.setStepName(definition.getName());
        run.setStatus(StepStatus.PENDING);
        run.setAttempt(0);
        return run;
    }

    public String getStepRunId() {
        return stepRunId;
    }

    public void setStepRunId(String stepRunId) {
        this.stepRunId = stepRunId;
    }

    public String getTaskRunId() {
        return taskRunId;
    }

    public void setTaskRunId(String taskRunId) {
        this.taskRunId = taskRunId;
    }

    public String getStepId() {
        return stepId;
    }

    public void setStepId(String stepId) {
        this.stepId = stepId;
    }

    public String getStepName() {
        return stepName;
    }

    public void setStepName(String stepName) {
        this.stepName = stepName;
    }

    public StepStatus getStatus() {
        return status;
    }

    public void setStatus(StepStatus status) {
        this.status = status;
    }

    public int getAttempt() {
        return attempt;
    }

    public void setAttempt(int attempt) {
        this.attempt = attempt;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(Instant completedAt) {
        this.completedAt = completedAt;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public void setErrorCode(String errorCode) {
        this.errorCode = errorCode;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public String getOutputArtifactId() {
        return outputArtifactId;
    }

    public void setOutputArtifactId(String outputArtifactId) {
        this.outputArtifactId = outputArtifactId;
    }

    public Long getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(Long durationMs) {
        this.durationMs = durationMs;
    }
}
