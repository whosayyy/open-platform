package com.yin.ai.platform.agent.pattern.executor;

import com.yin.ai.platform.core.runtime.step.StepExecutorResult;
import com.yin.ai.platform.agent.pattern.AgentExecutionContext;

import java.util.Map;

/**
 * 步骤执行器 SPI — 根据 executor 标识路由到具体执行逻辑。
 * <p>
 * 设计理由：PlanExecute / DeepResearch 的每步有不同的执行方式：
 * - "llm"：纯 LLM 生成
 * - "web_search"：调用搜索 Function
 * - "skill:xxx"：调用已注册的 Skill
 * - "tool:xxx"：调用已注册的 Tool/Function
 * <p>
 * 通过 Spring Bean 自动发现，StepExecutorDispatcher 按 executor 前缀匹配。
 */
public interface StepExecutor {

    /**
     * 该执行器支持的 executor 标识前缀。
     * 如 "llm"、"web_search"、"skill:"、"tool:"
     */
    String executorPrefix();

    /**
     * 执行步骤。
     *
     * @param executorId 完整 executor 标识（如 "skill:news-generator"）
     * @param inputs     由 ContextManager 解析后的输入
     * @param ctx        执行上下文
     * @return 执行输出
     */
    StepExecutorResult execute(String executorId, Map<String, String> inputs, AgentExecutionContext ctx);

    /** 优先级（同前缀多实现时） */
    default int priority() { return 100; }
}
