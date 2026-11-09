package com.yin.ai.platform.agent.pattern;

import java.util.Map;

/**
 * 统一任务请求 — UnifiedAgentRuntime.execute() 的入参。
 * <p>
 * 由 Controller / Service 层构造，封装用户请求的所有信息。
 *
 * @param goal         用户目标（自然语言）
 * @param userId       用户 ID
 * @param sessionId    会话 ID
 * @param routeContext 路由上下文（含前端信号）
 * @param metadata     额外元数据
 * @param systemPrompt Agent 专属系统提示词（由 agent.configure() 注入，null 则使用默认）
 * @param temperature  模型温度（由 agent.configure() 注入，null 则使用默认）
 * @param model        模型名称（如 deepseek-chat，null 则使用默认）
 */
public record AgentTaskRequest(
        String goal,
        String userId,
        String sessionId,
        RouteContext routeContext,
        Map<String, String> metadata,
        String systemPrompt,
        Double temperature,
        String model
) {
    public AgentTaskRequest(String goal, String userId, String sessionId,
                           RouteContext routeContext, Map<String, String> metadata) {
        this(goal, userId, sessionId, routeContext, metadata, null, null, null);
    }

    public static AgentTaskRequest of(String goal, String userId, String sessionId) {
        return new AgentTaskRequest(goal, userId, sessionId,
                RouteContext.of(userId, sessionId), Map.of());
    }

    public static AgentTaskRequest withDeepMode(String goal, String userId, String sessionId) {
        return new AgentTaskRequest(goal, userId, sessionId,
                RouteContext.withDeepMode(userId, sessionId), Map.of());
    }
}
