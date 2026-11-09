package com.yin.ai.platform.agent.task;

import com.yin.ai.platform.agent.pattern.AgentMode;

import java.util.Map;

/**
 * 任务创建请求 — 由 AgentRuntime 构造，传给 TaskEngine.create()。
 *
 * @param goal      用户目标（自然语言）
 * @param userId    用户 ID
 * @param sessionId 会话 ID
 * @param mode      路由决定的执行模式
 * @param metadata  额外元数据（如来源、优先级等）
 */
public record TaskCreateRequest(
        String goal,
        String userId,
        String sessionId,
        AgentMode mode,
        Map<String, String> metadata
) {
    public static TaskCreateRequest of(String goal, String userId, String sessionId, AgentMode mode) {
        return new TaskCreateRequest(goal, userId, sessionId, mode, Map.of());
    }

    public static TaskCreateRequest child(String goal, String userId, String sessionId,
                                          AgentMode mode, String parentTaskId,
                                          String rootTaskId, String agentRole,
                                          Map<String, String> metadata) {
        java.util.LinkedHashMap<String, String> values = new java.util.LinkedHashMap<>();
        if (metadata != null) values.putAll(metadata);
        values.put("parentTaskId", parentTaskId);
        values.put("rootTaskId", rootTaskId != null ? rootTaskId : parentTaskId);
        values.put("agentRole", agentRole);
        return new TaskCreateRequest(goal, userId, sessionId, mode, Map.copyOf(values));
    }
}
