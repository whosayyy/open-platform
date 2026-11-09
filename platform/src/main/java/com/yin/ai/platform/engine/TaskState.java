package com.yin.ai.platform.engine;

/**
 * AI 任务生命周期状态机。
 * 严格流转路径：
 * CREATED → RUNNING → THINKING → TOOL_CALLING → COMPLETED | CANCELLED | FAILED | TIMEOUT
 */
public enum TaskState {
    CREATED,
    RUNNING,
    THINKING,
    TOOL_CALLING,
    COMPLETED,
    CANCELLED,
    FAILED,
    TIMEOUT;

    public boolean isTerminal() {
        return this == COMPLETED || this == CANCELLED || this == FAILED || this == TIMEOUT;
    }
}
