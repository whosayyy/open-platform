package com.yin.ai.platform.agent.pattern;

import com.yin.ai.platform.core.agent.mode.AgentMode;

/**
 * 统一 Agent 路由器 — 收敛现有分散的路由逻辑。
 * <p>
 * 现有路由分散在：
 * - platform: ComplexityAnalyzer + StrategyRouter
 * - service: AgentRouter + LlmRouterService
 * <p>
 * 本接口统一收敛，设计原则：Rule 优先，LLM 兜底，不完全依赖 LLM。
 * 实现类 {@code DefaultAgentRouter} 组合多个 {@link ModeClassifier}。
 */
public interface AgentRouter {

    /**
     * 根据用户输入和上下文路由到最合适的执行模式。
     *
     * @param userInput 用户原始输入
     * @param ctx       路由上下文（含用户偏好、前端信号等）
     * @return 决定的执行模式
     */
    AgentMode route(String userInput, RouteContext ctx);
}
