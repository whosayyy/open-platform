package com.yin.ai.platform.agent.strategy;

import com.yin.ai.platform.agent.AgentContext;

/**
 * 复杂度分析器 SPI — 评估任务复杂度并建议执行策略。
 * <p>
 * 多个 Analyzer 按 priority 排序，取最高非 SIMPLE 判定（高置信度优先）。
 * 必须是纯函数：无副作用，不调用外部服务，5ms 内完成。
 */
public interface ComplexityAnalyzer {

    /** 分析任务复杂度。 */
    ComplexityAssessment assess(String userInput, AgentContext ctx);

    /** 优先级，值越大越优先。默认 0。 */
    default int priority() {
        return 0;
    }

    /** 分析器名称（日志/调试）。 */
    default String name() {
        return getClass().getSimpleName();
    }
}
