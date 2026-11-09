package com.yin.ai.platform.workflow.event;

/**
 * 执行事件类型 — Runtime 自动发布，不再由模型调用 reportPlan。
 */
public enum ExecutionEventType {

    TASK_CREATED,
    TASK_STARTED,

    PLAN_CREATED,

    STEP_READY,
    STEP_STARTED,
    STEP_PROGRESS,
    STEP_RETRYING,
    STEP_SUMMARY,

    TOOL_STARTED,
    TOOL_COMPLETED,
    TOOL_FAILED,

    ARTIFACT_CREATED,
    ARTIFACT_STARTED,
    ARTIFACT_DELTA,
    ARTIFACT_COMPLETED,
    STEP_COMPLETED,
    STEP_FAILED,

    TASK_WAITING_INPUT,
    TASK_SUMMARY,
    TASK_COMPLETED,
    TASK_FAILED,
    TASK_CANCELLED
}
