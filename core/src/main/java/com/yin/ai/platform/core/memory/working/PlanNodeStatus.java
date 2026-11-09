package com.yin.ai.platform.core.memory.working;

/**
 * PlanNode 执行状态 — COMPLETED/FAILED/SKIPPED 为终态。
 */
public enum PlanNodeStatus {
    PENDING,
    IN_PROGRESS,
    COMPLETED,
    FAILED,
    SKIPPED;

    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == SKIPPED;
    }
}
