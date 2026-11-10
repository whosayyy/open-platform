package com.yin.ai.platform.workflow.model;

/**
 * 任务运行状态 — 由 ExecutionRuntime 维护，禁止由 LLM 生成或修改。
 */
public enum TaskStatus {

    CREATED,
    PLANNING,
    RUNNING,
    WAITING_INPUT,
    SUCCEEDED,
    FAILED,
    CANCELLED;

    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED || this == CANCELLED;
    }
}
