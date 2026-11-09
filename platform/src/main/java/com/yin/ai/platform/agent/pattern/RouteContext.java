package com.yin.ai.platform.agent.pattern;

import java.util.Map;

/**
 * 路由上下文 — 携带路由决策所需的额外信号。
 *
 * @param userId                用户 ID
 * @param sessionId             会话 ID
 * @param userRequestedDeepMode 用户是否主动开启深度模式（前端开关）
 * @param hints                 前端传入的额外信号（如页面来源、历史模式偏好等）
 */
public record RouteContext(
        String userId,
        String sessionId,
        boolean userRequestedDeepMode,
        Map<String, Object> hints
) {
    public static RouteContext of(String userId, String sessionId) {
        return new RouteContext(userId, sessionId, false, Map.of());
    }

    public static RouteContext withDeepMode(String userId, String sessionId) {
        return new RouteContext(userId, sessionId, true, Map.of());
    }
}
