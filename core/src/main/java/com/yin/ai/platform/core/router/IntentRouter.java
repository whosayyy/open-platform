package com.yin.ai.platform.core.router;

import java.util.List;

/**
 * 意图路由器。
 * <p>
 * 遍历注册的 IntentMatcher（按 priority 降序），返回第一个匹配成功的 RouteResult。
 * 如果所有 Matcher 都返回空，返回默认意图 CHAT。
 */
public interface IntentRouter {

    /**
     * 匹配用户输入，返回路由结果。
     *
     * @param userInput 用户原始输入（纯文本）
     * @param ctx       路由上下文（sessionId, userId, metadata）
     * @return 路由结果（intent + handler + confidence）
     */
    RouteResult route(String userInput, RouteContext ctx);

    /**
     * 返回所有已注册的 Matcher（按 priority 降序）。
     */
    List<IntentMatcher> getMatchers();

    /**
     * 动态注册 Matcher（SPI 扩展点）。
     */
    void registerMatcher(IntentMatcher matcher);
}
