package com.yin.ai.platform.agent.task;

/**
 * 平台级任务生命周期状态机。
 * <p>
 * 与现有 {@code AgentState}（ReAct 循环内部状态）的关系：
 * - AgentState 管理单次 ReAct Loop 内的微观状态（ACTING/OBSERVING/REFLECTING）
 * - TaskState 管理整个任务的宏观生命周期（可能包含多轮 ReAct）
 * <p>
 * 合法转换路径：
 * <pre>
 * CREATED → PLANNING → RUNNING → COMPLETED
 *                  ↘         ↗
 *                   → RUNNING → WAITING → RUNNING
 *                            ↘
 *                             → FAILED → RUNNING (重试)
 * 任何非终态 → CANCELLED
 * </pre>
 */
public enum TaskState {

    /** 任务已创建，未开始执行 */
    CREATED,

    /** 正在生成执行计划（PLAN_EXECUTE / DEEP_RESEARCH） */
    PLANNING,

    /** 正在执行步骤 */
    RUNNING,

    /** 等待外部输入（HITL 审批 / 用户确认） */
    WAITING,

    /** 成功完成（终态） */
    COMPLETED,

    /** 执行失败（允许重试 → RUNNING） */
    FAILED,

    /** 已取消（终态） */
    CANCELLED;

    /** 是否为终态 */
    public boolean isTerminal() {
        return this == COMPLETED || this == CANCELLED;
    }

    /** 合法状态转换校验 */
    public boolean canTransitionTo(TaskState target) {
        return switch (this) {
            case CREATED -> target == PLANNING || target == RUNNING || target == WAITING || target == CANCELLED;
            case PLANNING -> target == RUNNING || target == WAITING || target == FAILED || target == CANCELLED;
            case RUNNING -> target == WAITING || target == COMPLETED || target == FAILED || target == CANCELLED;
            case WAITING -> target == RUNNING || target == CANCELLED;
            case FAILED -> target == RUNNING || target == CANCELLED;  // 允许重试
            case COMPLETED, CANCELLED -> false;
        };
    }
}
