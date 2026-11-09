package com.yin.ai.platform.core.agent.plan;

/** Lifecycle of a framework-neutral plan step. */
public enum PlanStepStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    FAILED,
    SKIPPED
}
