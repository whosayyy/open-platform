package com.yin.ai.platform.agent.event;

/**
 * 统一任务事件类型 — 覆盖任务生命周期 + 步骤执行 + 产物更新。
 * <p>
 * 合并现有 {@code AgentEventType}（ReAct 内部事件）和新增的平台级事件。
 * 所有事件通过 {@link EventPublisher} 发布，双写 Redis Stream + SSE Sink。
 */
public enum TaskEventType {

    // === 任务生命周期 ===
    TASK_CREATED,
    TASK_PLANNING,
    TASK_RUNNING,
    TASK_WAITING,
    TASK_COMPLETED,
    TASK_FAILED,
    TASK_CANCELLED,
    TASK_RESUMED,
    TASK_SUMMARY,

    // === 步骤级 ===
    STEP_STARTED,
    STEP_PROGRESS,
    STEP_RESULT,
    /** Markdown summary token/chunk. Contains no raw reasoning or worker JSON. */
    STEP_SUMMARY_DELTA,
    STEP_SUMMARY,
    STEP_COMPLETED,
    STEP_FAILED,
    STEP_SKIPPED,
    /** LLM-selected reviewable milestone; never raw tool output or every step output. */
    STEP_PARTIAL_ARTIFACT,

    // === 工具与推理（不包含模型思维正文） ===
    TOOL_STARTED,
    TOOL_COMPLETED,
    TOOL_FAILED,
    REASONING_STARTED,
    REASONING_COMPLETED,

    // === 计划 ===
    PLAN_CREATED,
    PLAN_SUMMARY,
    PLAN_UPDATED,

    // === Multi-agent team ===
    TEAM_PLAN_CREATED,
    SUBTASK_CREATED,
    SUBTASK_STARTED,
    SUBTASK_COMPLETED,
    SUBTASK_FAILED,
    TEAM_PARTIAL,

    // === 产物 ===
    ARTIFACT_CREATED,
    ARTIFACT_UPDATED,
    REFERENCES_FOUND,

    // === 审批 ===
    APPROVAL_REQUIRED,
    APPROVAL_RESOLVED,

    // === 上下文 ===
    CONTEXT_UPDATED,

    // === 心跳/进度 ===
    PROGRESS,
    HEARTBEAT
}
