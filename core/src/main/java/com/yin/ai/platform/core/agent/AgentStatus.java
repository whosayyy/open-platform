package com.yin.ai.platform.core.agent;

/** Stable lifecycle states exposed by the core API. */
public enum AgentStatus {
    CREATED,
    RUNNING,
    WAITING,
    COMPLETED,
    FAILED,
    CANCELLED
}
