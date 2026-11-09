package com.yin.ai.platform.agent;

/**
 * Agent 流式事件类型。
 */
public enum AgentEventType {
    PLAN_START,
    PLAN_COMPLETE,
    STEP_START,
    TOOL_CALL_START,
    TOOL_CALL_RESULT,
    OBSERVE_RESULT,
    REFLECT_DECISION,
    STEP_COMPLETE,
    TEXT_CHUNK,
    REASONING_CHUNK,
    RAG_REFERENCES,
    HITL_PENDING,
    AGENT_COMPLETE,
    AGENT_ERROR
}
