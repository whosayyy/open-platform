package com.yin.ai.platform.agent.strategy;

import com.yin.ai.platform.agent.AgentContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Comparator;
import java.util.List;

/**
 * 策略路由器 — 收集所有 ComplexityAnalyzer 的评估结果，按优先级 + 置信度决定执行策略。
 */
public class StrategyRouter {

    private static final Logger log = LoggerFactory.getLogger(StrategyRouter.class);

    private final List<ComplexityAnalyzer> analyzers;
    private final ComplexityConfig config;

    public StrategyRouter(List<ComplexityAnalyzer> analyzers, ComplexityConfig config) {
        this.analyzers = analyzers.stream()
                .sorted(Comparator.comparingInt(ComplexityAnalyzer::priority).reversed())
                .toList();
        this.config = config;
    }

    /**
     * 评估复杂度并返回完整评估结果。
     */
    public ComplexityAssessment assess(String userInput, AgentContext ctx) {
        if (!config.enabled() || analyzers.isEmpty()) {
            return ComplexityAssessment.simple("disabled or no analyzers");
        }

        ComplexityAssessment best = null;

        for (ComplexityAnalyzer analyzer : analyzers) {
            ComplexityAssessment assessment;
            try {
                assessment = analyzer.assess(userInput, ctx);
            } catch (Exception e) {
                log.warn("[STRATEGY-ROUTER] analyzer={} error: {}", analyzer.name(), e.getMessage());
                continue;
            }

            log.debug("[STRATEGY-ROUTER] analyzer={} level={} confidence={} reason={}",
                    analyzer.name(), assessment.level(), assessment.confidence(), assessment.reason());

            if (best == null || assessment.confidence() > best.confidence()
                    || (assessment.level() != ComplexityLevel.SIMPLE && best.level() == ComplexityLevel.SIMPLE)) {
                best = assessment;
            }
        }

        return best != null ? best : ComplexityAssessment.simple("default");
    }

    /**
     * 根据用户输入和上下文路由到对应的执行策略。
     */
    public ExecutionStrategy route(String userInput, AgentContext ctx) {
        ComplexityAssessment assessment = assess(userInput, ctx);

        if (assessment.level() != ComplexityLevel.SIMPLE
                && assessment.confidence() >= config.moderateThreshold()) {
            log.info("[STRATEGY-ROUTER] input=\"{}\" strategy={} level={} confidence={}",
                    truncate(userInput, 80), assessment.suggested(),
                    assessment.level(), assessment.confidence());
            return assessment.suggested();
        }

        ExecutionStrategy defaultStrategy = config.strategyMapping() != null
                ? config.strategyMapping().getOrDefault(ComplexityLevel.SIMPLE, ExecutionStrategy.DIRECT_SKILL)
                : ExecutionStrategy.DIRECT_SKILL;

        log.debug("[STRATEGY-ROUTER] input=\"{}\" strategy={} (default)",
                truncate(userInput, 80), defaultStrategy);
        return defaultStrategy;
    }

    /**
     * 【企业级缺口修复】策略路由是否真正生效。
     * <p>
     * 仅当 config.enabled 且存在至少一个 analyzer 时为 true —— 此时 assess() 的结果
     * 是真实评估而非"disabled or no analyzers"兜底值。调用方（DefaultAgentRuntime）
     * 用它防止"未配置 analyzer 时误走 DIRECT_SKILL 快捷路径"的行为回归。
     */
    public boolean isActive() {
        return config.enabled() && !analyzers.isEmpty();
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "null";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}
