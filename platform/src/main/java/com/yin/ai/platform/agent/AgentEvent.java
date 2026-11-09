package com.yin.ai.platform.agent;

/**
 * Agent 流式执行事件。
 *
 * @param type      事件类型
 * @param stepIndex 当前步骤索引（0-based）
 * @param data      事件数据（JSON payload）
 * @param timestamp 事件时间戳（epoch millis）
 */
public record AgentEvent(
        AgentEventType type,
        int stepIndex,
        String data,
        long timestamp) {

    public static AgentEvent of(AgentEventType type, int stepIndex, String data) {
        return new AgentEvent(type, stepIndex, data, System.currentTimeMillis());
    }
}
