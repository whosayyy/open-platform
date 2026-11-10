package com.yin.ai.platform.workflow.model;

/**
 * 步骤运行状态 — 由 Runtime 完成全部状态迁移，并做合法性校验。
 */
public enum StepStatus {

    PENDING,
    READY,
    RUNNING,
    SUCCEEDED,
    FAILED,
    BLOCKED,
    CANCELLED,
    SKIPPED;

    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED || this == CANCELLED || this == SKIPPED;
    }

    /**
     * 状态迁移合法性表。key = 当前状态，value = 允许迁移到的状态集合。
     */
    public boolean canTransitionTo(StepStatus target) {
        if (this == target) {
            return true;
        }
        return switch (this) {
            case PENDING -> target == READY || target == BLOCKED || target == SKIPPED;
            case READY -> target == RUNNING || target == SKIPPED || target == BLOCKED;
            case RUNNING -> target == SUCCEEDED || target == FAILED || target == CANCELLED;
            case SUCCEEDED, FAILED, CANCELLED, SKIPPED -> false;
            case BLOCKED -> target == PENDING || target == READY || target == SKIPPED;
        };
    }
}
